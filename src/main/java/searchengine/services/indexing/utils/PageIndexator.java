package searchengine.services.indexing.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.springframework.http.HttpStatus;
import searchengine.dto.indexing.*;
import searchengine.mappers.SiteMapper;
import searchengine.model.*;
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

    private static final String processStartedMessage = "Выполняется обработка страницы %s";
    private static final String processFinishedMessage = "Закончена обработка страницы %s";
    private static final String siteNotFoundMessage = "Сайт не найден в БД! %s";
    private static final String mainPageNotAvailableMessage = "Главная страница сайта %s не отвечает!";
    private static final String siteIndexationFinishedMessage = "Закончена индексация сайта с url=%s";
    private static final String taskCancellationMessage = "Выполнение дочерних PageIndexator задач прервано! %s";
    private static final String taskProcessingErrorMessage = "При выполнении задачи произошла ошибка: %s";

    @Getter public final String currentLink;  // текущая полная ссылка
    private final LemmasService lemmasService;  // сервис лемматизации
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final HttpJsoupConnector httpJsoupConnector;  // сервис запросов к сайту
    private final CopyOnWriteArrayList<PageIndexator> siteTaskList;  // для отслеживания оставшихся задач по сайту
    private final boolean onlyThisPageIndex;  // флаг для индексации/обновления одной конкретной страницы

    private SiteDto siteDto;  // инфо сайта
    private List<PageIndexator> nextTasks;  // для запуска оставшихся задач

    /* Общий конструктор для потомков и корня сайта */
    public PageIndexator(SiteDto siteDto, String currentLink, HttpJsoupConnector httpJsoupConnector,
                         CopyOnWriteArrayList<PageIndexator> siteTaskList, LemmasService lemmasService,
                         SiteRepository siteRepository, PageRepository pageRepository, IndexRepository indexRepository,
                         boolean onlyThisPageIndex) {
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.indexRepository = indexRepository;
        this.siteDto = new SiteDto();
        this.siteDto.setId(siteDto.getId());
        this.siteTaskList = siteTaskList;
        siteTaskList.add(this);
        this.currentLink = currentLink;
        this.httpJsoupConnector = httpJsoupConnector;
        this.lemmasService = lemmasService;
        this.onlyThisPageIndex = onlyThisPageIndex;
    }

    /* Конструктор для корня индексации */
    public PageIndexator(SiteDto siteDto, String currentLink, LemmasService lemmasService,
                         SiteRepository siteRepository, PageRepository pageRepository, IndexRepository indexRepository,
                         boolean onlyThisPageIndex) {
        this(siteDto, currentLink, new HttpJsoupConnector(), new CopyOnWriteArrayList<>(),
                lemmasService, siteRepository, pageRepository, indexRepository, onlyThisPageIndex);
    }

    @Override
    protected CopyOnWriteArraySet<String> compute() {
        try {
            startIndexingProcess();
            if (nextTasks != null) nextTasks.forEach(PageIndexator::fork);
        } catch (CancellationException e) {
            log.warn("[" + currentLink + "] " + taskCancellationMessage.formatted(e.getLocalizedMessage()));
        } catch (RuntimeException e) {
            log.error("[" + currentLink + "] " + taskProcessingErrorMessage.formatted(e.getLocalizedMessage()));
        }
        return new CopyOnWriteArraySet<>();
    }

    private Set<String> startIndexingProcess() {
        log.info(processStartedMessage.formatted(currentLink));

        updateSiteDto();
        if (isSiteFail()) return exitByStatus(SiteStatus.FAILED, null);

        String relativePath = currentLink.substring(siteDto.getUrl().length());
        relativePath = relativePath.isEmpty() ? "/" : relativePath;
        Optional<Page> optionalPage = findExistingPage(relativePath);

        if (optionalPage.isPresent() && !onlyThisPageIndex) return exitByStatus(SiteStatus.INDEXING, null);
        else if (optionalPage.isPresent()) {
            handlePreviousLemmas(optionalPage.get());
            removePageFromRepository(relativePath, SiteMapper.INSTANCE.siteDtoToSite(siteDto));
        }

        PageDto pageDto;
        try {
            pageDto = httpJsoupConnector  // прерывание по времени на HttpJsoupConnectorService.REQUEST_TIMEOUT (мс)
                    .getPageDtoFromLink(currentLink);
        } catch (InterruptedException e) {
            log.warn(e.getLocalizedMessage());
            return exitByStatus(SiteStatus.FAILED, IndexingService.INDEXING_STOPPED_BY_USER_MESSAGE);
        }

        updateSiteDto();
        if (isSiteFail()) return exitByStatus(SiteStatus.FAILED, null);

        pageDto.setPath(relativePath);
        saveAndUpdatePageDto(pageDto);

        if (pageDto.getCode() != HttpStatus.OK.value()) {
            if (pageDto.getPath().equals("/")) {
                return exitByStatus(SiteStatus.FAILED, mainPageNotAvailableMessage.formatted(siteDto.getUrl()));
            }
            return exitByStatus(SiteStatus.INDEXING, null);
        }
        if (pageDto.getCode() < HttpStatus.BAD_REQUEST.value()) indexPage(pageDto);  // индексируются только коды < 400
        if (!onlyThisPageIndex) nextTasks = prepareNextTasks(pageDto);
        return exitByStatus(SiteStatus.INDEXED, null);
    }

    private void updateSiteDto() {
        if (siteDto == null) {
            log.error(siteNotFoundMessage.formatted("[" + currentLink + "]"));
            return;
        }
        try {
            Site site = siteRepository.findById(siteDto.getId())
                    .orElseThrow();
            siteDto = SiteMapper.INSTANCE.siteToSiteDto(site);
        } catch (NoSuchElementException e) {
            siteDto = null;
        }
    }

    private boolean isSiteFail() {
        return siteDto == null ||
                siteDto.getId() == null ||
                siteDto.getStatus() != SiteStatus.INDEXING;
    }

    private Set<String> exitByStatus(SiteStatus preferredStatus, String errorText) {
        siteTaskList.removeIf(task -> task.getCurrentLink().equals(currentLink));
        int count = siteTaskList.size();

        updateSiteDto();
        if (siteDto == null) return new HashSet<>();

        switch (siteDto.getStatus()) {
            case FAILED -> {
                preferredStatus = SiteStatus.FAILED;
                errorText = null;
            }
            case INDEXING, INDEXED -> {
                if (preferredStatus != SiteStatus.FAILED && count > 0) {
                    preferredStatus = SiteStatus.INDEXING;
                    log.info(processFinishedMessage.formatted(currentLink) +
                            ", осталось активных задач по сайту: " + count);
                } else if (preferredStatus != SiteStatus.FAILED) {
                    preferredStatus = SiteStatus.INDEXED;
                    log.info(siteIndexationFinishedMessage.formatted(siteDto.getUrl()));
                }
            }
        }

        switch (preferredStatus) {
            case FAILED -> {
                siteDto.setFailed(errorText);
                if (errorText!= null && !errorText.isEmpty()) log.error(errorText + " [" + currentLink + "]");
            }
            case INDEXED -> siteDto.setIndexed();
            case INDEXING -> siteDto.updateStatusTime();
        }

        Site site = siteRepository.saveAndFlush(SiteMapper.INSTANCE.siteDtoToSite(siteDto));
        if (site.getStatus() == SiteStatus.FAILED) siteTaskList.clear();
        return new HashSet<>();
    }

    private Optional<Page> findExistingPage(String path) {
        return pageRepository.findAllBySiteAndPath(SiteMapper.INSTANCE.siteDtoToSite(siteDto), path).stream()
                .findFirst();
    }

    private Set<Integer> getPreviousLemmasIdSetByPage(Page page) {
        Set<Index> pageIndexes = indexRepository.findAllByPageId(page.getId());
        return pageIndexes.stream()
                .map(Index::getLemmaId)
                .collect(Collectors.toSet());
    }

    private void handlePreviousLemmas(Page page) {
        Set<Integer> previousLemmasIds = getPreviousLemmasIdSetByPage(page);
        lemmasService.decrementLemmasFrequencyOrRemoveByIds(previousLemmasIds);
    }

    private void removePageFromRepository(String relativePath, Site site) {
        Set<Page> foundPages = pageRepository.findAllBySiteAndPath(site, relativePath);
        pageRepository.deleteAll(foundPages);
    }

    private void saveAndUpdatePageDto(PageDto pageDto) {
        pageDto.setSite(SiteMapper.INSTANCE.siteDtoToSite(siteDto));
        pageDto.setId(pageRepository.saveAndFlush(pageDto.toEntity()).getId());
    }

    private void indexPage(PageDto pageDto) {
        Map<String, Integer> foundLemmas = lemmasService.collectLemmas(pageDto.getContent());
        List<Lemma> lemmaEntitiesToIndex = lemmasService.handleLemmas(siteDto, foundLemmas.keySet());
        int count = IndexingService.indexLemmas(indexRepository, lemmaEntitiesToIndex, foundLemmas, pageDto);
        log.info("Проиндексировали " + count + " новых лемм со страницы \"" +
                pageDto.getPath() +"\" сайта "+ siteDto.getUrl());
    }

    private Set<String> getExistingAndProcessingSiteLinks() {
        Set<String> linksInProcess = siteTaskList.stream()
                .map(PageIndexator::getCurrentLink)
                .collect(Collectors.toSet());  // сначала находим обрабатываемые ссылки
        Set<Page> existingPages = pageRepository.findAllBySite(SiteMapper.INSTANCE.siteDtoToSite(siteDto));
        linksInProcess.addAll(existingPages.stream()
                .map(page -> siteDto.getUrl().concat(page.getPath()))
                .collect(Collectors.toSet()));  // добавляем к ним из БД
        return linksInProcess;
    }

    private List<PageIndexator> prepareNextTasks(PageDto pageDto) {
        Set<String> nextLinksToIndex = new HashSet<>(getValidAndFormattedLinks(pageDto.getLinks()));
        log.info("На странице [" + currentLink + "] найдено " + nextLinksToIndex.size() + " уникальных ссылок");
        nextLinksToIndex.removeAll(getExistingAndProcessingSiteLinks());
        return nextLinksToIndex.stream()
                .map(link -> new PageIndexator(siteDto, link, httpJsoupConnector, siteTaskList, lemmasService,
                        siteRepository, pageRepository, indexRepository, false))
                .toList();
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
            if (!isValidLink(linkValue)) continue;
            foundLinks.add(getFormattedLink(linkValue));
        }
        return foundLinks;
    }

}
