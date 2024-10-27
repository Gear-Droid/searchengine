package searchengine.services.indexing.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.springframework.data.domain.Example;
import org.springframework.data.repository.Repository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.indexing.*;
import searchengine.model.*;
import searchengine.services.http.*;
import searchengine.services.morphology.LemmasService;
import searchengine.services.indexing.IndexingService;
import searchengine.repositories.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RecursiveTask;
import java.util.stream.Collectors;

@Slf4j
public class PageIndexator extends RecursiveTask<CopyOnWriteArraySet<String>> {

    private static final String PROCESS_STARTED_MESSAGE = "Выполняется обработка страницы %s";
    private static final String PROCESS_FINISHED_MESSAGE = "Закончена обработка страницы %s";
    private static final String SITE_NOT_FOUND_MESSAGE = "Сайт не найден в БД! %s";
    private static final String MAIN_PAGE_NOT_AVAILABLE_MESSAGE = "Главная страница сайта %s не отвечает!";
    private static final String SITE_INDEXATION_FINISHED_MESSAGE = "Закончена индексация сайта с url=%s";
    private static final String TASK_CANCELLATION_MESSAGE = "Выполнение дочерних PageIndexator задач прервано! %s";

    @Getter public final String currentLink;  // текущая полная ссылка
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final LemmasService lemmasService;  // сервис лемматизации
    private final HttpJsoupConnectorService httpService;  // сервис запросов к сайту
    private final CopyOnWriteArrayList<PageIndexator> siteTaskList;  // для отслеживания оставшихся задач по сайту
    private final boolean onlyThisPageIndex;  // флаг для индексации/обновления одной конкретной страницы

    private SiteDto siteDto;  // инфо сайта
    private List<PageIndexator> nextTasks;  // для запуска оставшихся задач

    /* Общий конструктор для потомков и корня сайта */
    public PageIndexator(SiteDto siteDto, String currentLink, HttpJsoupConnectorService httpService,
                         CopyOnWriteArrayList<PageIndexator> siteTaskList, LemmasService lemmasService,
                         boolean onlyThisPageIndex) {
        // для удобной передачи в конструктор потомков
        Map<String, Repository> repositories = IndexingService.repositories;
        this.siteRepository = (SiteRepository) repositories.get("siteRepository");
        this.pageRepository = (PageRepository) repositories.get("pageRepository");
        this.indexRepository = (IndexRepository) repositories.get("indexRepository");

        this.siteDto = new SiteDto();
        this.siteDto.setId(siteDto.getId());

        this.siteTaskList = siteTaskList;
        siteTaskList.add(this);

        this.currentLink = currentLink;
        this.httpService = httpService;
        this.lemmasService = lemmasService;
        this.onlyThisPageIndex = onlyThisPageIndex;
    }

    /* Конструктор для корня индексации */
    public PageIndexator(SiteDto siteDto, String currentLink,
                         LemmasService lemmasService, boolean onlyThisPageIndex) {
        this(siteDto, currentLink, new HttpJsoupConnectorServiceImpl(),
                new CopyOnWriteArrayList<>(), lemmasService, onlyThisPageIndex);
    }

    @Override
    protected CopyOnWriteArraySet<String> compute() {
        try {
            startIndexingProcess();
            if (nextTasks != null && !nextTasks.isEmpty()) {
                nextTasks.forEach(PageIndexator::fork);
            }
        } catch (CancellationException e) {
            log.warn(TASK_CANCELLATION_MESSAGE.formatted(e.getLocalizedMessage()));
        } catch (RuntimeException e) {
            log.error("[" + currentLink + "] При выполнении задачи произошла ошибка: " + e.getLocalizedMessage());
        }
        return new CopyOnWriteArraySet<>();
    }

    @Transactional
    private void startIndexingProcess() {
        log.info(PROCESS_STARTED_MESSAGE.formatted(currentLink));

        updateSiteDto();
        if (isSiteFail()) {
            exitByStatus(SiteStatus.FAILED, null);
            return;
        }

        String relativePath = currentLink.substring(siteDto.getUrl().length());
        relativePath = relativePath.isEmpty() ? "/" : relativePath;
        Optional<Page> optionalPage = findExistingPage(relativePath);

        if (optionalPage.isPresent() && onlyThisPageIndex) {
            synchronized (optionalPage.get()) {  // блокировка по странице
                handlePreviousLemmas(optionalPage.get());
            }
            synchronized (httpService) {  // блокировка по сайту (уникальному http сервису сайта)
                removePageFromRepository(relativePath, IndexingService.siteDtoToSite(siteDto));
            }
        } else if (optionalPage.isPresent()) {
            exitByStatus(SiteStatus.INDEXING, null);
            return;
        }

        PageDto pageDto;
        try {
            pageDto = httpService  // есть прерывание по времени на HttpJsoupConnectorService.REQUEST_TIMEOUT (мс)
                    .getPageDtoFromLink(currentLink);
        } catch (InterruptedException e) {
            System.out.println("прерывание" + e);
            exitByStatus(SiteStatus.FAILED, IndexingService.INDEXING_STOPPED_BY_USER_MESSAGE);
            return;
        }

        updateSiteDto();
        if (isSiteFail()) {
            exitByStatus(SiteStatus.FAILED, null);
            return;
        }

        pageDto.setPath(relativePath);
        saveAndUpdatePageDto(pageDto);

        if (pageDto.getCode() != HttpStatus.OK.value()) {
            if (pageDto.getPath().equals("/")) {
                exitByStatus(SiteStatus.FAILED, MAIN_PAGE_NOT_AVAILABLE_MESSAGE.formatted(siteDto.getUrl()));
                return;
            }
            exitByStatus(SiteStatus.INDEXING, null);
            return;
        }

        if (pageDto.getCode() < HttpStatus.BAD_REQUEST.value()) {  // индексируются только коды < 400
            indexPage(pageDto);
        }

        Set<String> nextLinksToIndex = new HashSet<>(  // сет для ссылок, которые еще нужно обработать
                getValidAndFormattedLinks(pageDto.getLinks()));

        if (!onlyThisPageIndex) {
            log.info("На странице [" + currentLink + "] найдено " +
                    nextLinksToIndex.size() + " уникальных ссылок");
            nextTasks = prepareNextTasks(nextLinksToIndex);
        }

        exitByStatus(SiteStatus.INDEXED, null);
    }

    private void updateSiteDto() {
        if (siteDto == null) return;

        try {
            Site site = siteRepository.findById(siteDto.getId())
                    .orElseThrow();
            siteDto = IndexingService.siteToSiteDto(site);
        } catch (NoSuchElementException e) {
            siteDto = null;
        }
    }

    private boolean isSiteFail() {
        return siteDto == null ||
                siteDto.getId() == null ||
                siteDto.getStatus() != SiteStatus.INDEXING;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    private Set<String> exitByStatus(SiteStatus preferredStatus, String errorText) {
        int count;
        synchronized (httpService) {
            siteTaskList.removeIf(task -> task.getCurrentLink().equals(currentLink));
            count = siteTaskList.isEmpty() ? 0 : siteTaskList.size();
        }

        updateSiteDto();
        if (siteDto == null) {
            log.error(SITE_NOT_FOUND_MESSAGE.formatted("[" + currentLink + "]"));
            return new HashSet<>();
        }

        SiteStatus siteStatus = siteDto.getStatus();
        switch (siteStatus) {
            case FAILED -> {
                log.warn("Выход по причине " + siteStatus.name() +
                        " статуса индексируемого сайта [" + currentLink + "]");
                return new HashSet<>();
            }
            case INDEXING, INDEXED -> {
                if (count > 1) {
                    preferredStatus = SiteStatus.INDEXING;
                    log.info(PROCESS_FINISHED_MESSAGE.formatted(currentLink) +
                            ", осталось активных задач по сайту: " + count);
                } else {
                    if (preferredStatus != SiteStatus.FAILED) preferredStatus = SiteStatus.INDEXED;
                    log.info(SITE_INDEXATION_FINISHED_MESSAGE.formatted(siteDto.getUrl()));
                }
            }
        }

        switch (preferredStatus) {
            case FAILED -> {
                siteDto.setFailed(errorText);
                if (errorText!= null && !errorText.isEmpty()) log.error(errorText);
            }
            case INDEXED -> siteDto.setIndexed();
            case INDEXING -> siteDto.updateStatusTime();
        }

        siteRepository.saveAndFlush(IndexingService.siteDtoToSite(siteDto));
        return new HashSet<>();
    }

    private Optional<Page> findExistingPage(String path) {
        return pageRepository.findAllBySiteAndPath(IndexingService.siteDtoToSite(siteDto), path).stream()
                .findFirst();
    }

    private Set<Integer> getPreviousLemmasIdSetByPage(Page page) {
        Set<Index> pageIndexes = indexRepository.findAllByPageId(page.getId());
        return pageIndexes.stream()
                .map(Index::getLemmaId)
                .collect(Collectors.toSet());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    private void handlePreviousLemmas(Page page) {
        Set<Integer> previousLemmasIds = getPreviousLemmasIdSetByPage(page);
        lemmasService.decrementLemmasFrequencyOrRemoveByIds(previousLemmasIds);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    private void removePageFromRepository(String relativePath, Site site) {
        Page searchPage = new Page();
        searchPage.setPath(relativePath);
        searchPage.setSite(site);
        Optional<Page> optionalPageToDelete = pageRepository.findAll(Example.of(searchPage)).stream()
                .findFirst();
        optionalPageToDelete.ifPresent(page -> pageRepository.deleteById(page.getId()));
    }

    private void saveAndUpdatePageDto(PageDto pageDto) {
        pageDto.setSite(IndexingService.siteDtoToSite(siteDto));
        Page page = pageRepository.saveAndFlush(pageDto.toEntity());
        pageDto.setId(page.getId());
    }

    private void indexPage(PageDto pageDto) {
        synchronized (indexRepository) {
            Map<String, Integer> foundLemmas = lemmasService.collectLemmas(pageDto.getContent());
            List<Lemma> lemmaEntitiesToIndex = lemmasService.handleLemmas(foundLemmas, siteDto);
            int count = IndexingService.indexLemmas(indexRepository, lemmaEntitiesToIndex, foundLemmas, pageDto);
            log.info("Проиндексировали " + count + " новых лемм со страницы \"" +
                    pageDto.getPath() +"\" сайта "+ siteDto.getUrl());
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE, readOnly = true)
    private Set<String> getExistingAndProcessingSiteLinks() {
        Set<String> processedLinks = siteTaskList.stream()
                .map(PageIndexator::getCurrentLink)
                .collect(Collectors.toSet());  // сначала находим обрабатываемые ссылки
        Set<Page> existingPages = pageRepository.findAllBySite(IndexingService.siteDtoToSite(siteDto));
        processedLinks.addAll(existingPages.stream()
                .map(page -> siteDto.getUrl().concat(page.getPath()))
                .collect(Collectors.toSet()));  // добавляем к ним из БД
        return processedLinks;
    }

    private List<PageIndexator> prepareNextTasks(Set<String> nextLinksToIndex) {
        if (nextLinksToIndex.isEmpty()) return List.of();

        synchronized (lemmasService) {
            Set<String> processedLinks = getExistingAndProcessingSiteLinks();
            nextLinksToIndex.removeAll(processedLinks);

            return nextLinksToIndex.stream()
                    .map(link -> new PageIndexator(
                            siteDto, link, httpService, siteTaskList, lemmasService, false))
                    .toList();
        }
    }

    private boolean isValidLink(String linkValue) {
        if (linkValue.length() <= 1 ||
                linkValue.startsWith("javascript") ||
                linkValue.startsWith("tel") ||
                linkValue.startsWith("mailto")) return false;

        if (linkValue.startsWith("http")) return linkValue.contains(siteDto.getUrl());

        return true;
    }

    private String getFormattedLink(String linkValue) {
        if (linkValue.startsWith("http")) {
            if (!linkValue.contains("www.")) {
                String[] splitResult = linkValue.split("://");
                // добавляем "www." если отсутствует
                linkValue = splitResult[0].concat("://www.").concat(splitResult[1]);
            }
        } else {
            // добавляем site url, если ссылка относительная
            linkValue = siteDto.getUrl().concat(linkValue);
        }

        // избавляемся от внутренних ссылок
        if (linkValue.contains("#")) {
            linkValue = linkValue.split("#")[0];
        }

        // очищаем query параметры
        if (linkValue.contains("?")) {
            linkValue = linkValue.split("\\?")[0];
        }

        // заменяем "//.../" -> "/"
        linkValue = "https://".concat(
            linkValue.substring("https://".length()).replaceAll("/+/", "/")
        );

        return linkValue.endsWith("/") ? linkValue : linkValue.concat("/");
    }

    private Set<String> getValidAndFormattedLinks(Elements links) {
        Set<String> foundLinks = new HashSet<>();

        for (Element link : links) {
            Attribute linkAttribute = link.attribute("href");
            String linkValue;
            try {
                linkValue = linkAttribute.getValue();
            } catch (NullPointerException e) {
                continue;
            }

            if (!isValidLink(linkValue)) {
                continue;
            }

            String newValue = getFormattedLink(linkValue);
            foundLinks.add(newValue);
        }

        return foundLinks;
    }

}
