package searchengine.services.indexing.utils;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.springframework.data.domain.Example;
import org.springframework.data.repository.Repository;
import org.springframework.http.HttpStatus;
import searchengine.dto.indexing.*;
import searchengine.model.*;
import searchengine.services.http.*;
import searchengine.services.morphology.LemmasService;
import searchengine.services.indexing.IndexingService;
import searchengine.repositories.*;

import java.util.*;
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

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final LemmasService lemmasService;  // сервис лемматизации
    private final HttpJsoupConnectorService httpService;  // сервис запросов к сайту
    private final boolean onlyThisPageIndex;  // флаг для индексации/обновления одной конкретной страницы
    private final String currentLink;  // текущая полная ссылка

    private SiteDto siteDto;  // инфо сайта
    @Setter private CopyOnWriteArrayList<PageIndexator> siteTaskList;  // для отслеживания оставшихся задач по сайту

    /* Общий конструктор для потомков и корня сайта */
    public PageIndexator(SiteDto siteDto, String currentLink, HttpJsoupConnectorService httpService,
                         LemmasService lemmasService, boolean onlyThisPageIndex) {
        // для удобной передачи в конструктор потомков
        Map<String, Repository> repositories = IndexingService.repositories;
        this.siteRepository = (SiteRepository) repositories.get("siteRepository");
        this.pageRepository = (PageRepository) repositories.get("pageRepository");
        this.indexRepository = (IndexRepository) repositories.get("indexRepository");

        this.siteDto = siteDto;
        this.currentLink = currentLink;
        this.httpService = httpService;
        this.lemmasService = lemmasService;
        this.onlyThisPageIndex = onlyThisPageIndex;
    }

    /* Конструктор для корня сайта */
    public PageIndexator(SiteDto siteDto, String currentLink,
                         LemmasService lemmasService, boolean onlyThisPageIndex) {
        this(siteDto, currentLink, new HttpJsoupConnectorServiceImpl(),
                lemmasService, onlyThisPageIndex);
    }

    @Override
    protected CopyOnWriteArraySet<String> compute() {
        log.info(PROCESS_STARTED_MESSAGE.formatted(currentLink));
        siteTaskList.add(this);

        try {
            Site site = getSiteEntityOrThrowNoSuchElementException();
            siteDto = IndexingService.siteToSiteDto(site);
        } catch (NoSuchElementException e) {
            return exit(true, e.getLocalizedMessage());
        }

        if (siteDto.getStatus() != SiteStatus.INDEXING) return exit(false, null);

        String relativePath = currentLink.substring(siteDto.getUrl().length());
        relativePath = relativePath.isEmpty() ? "/" : relativePath;
        Optional<Page> optionalPage = getOptionalPage(relativePath, IndexingService.siteDtoToSite(siteDto));

        boolean isExistsInRepository = optionalPage.isPresent();
        if (isExistsInRepository) {
            Set<Integer> previousLemmasIds = getPreviousLemmasIdSet(optionalPage.get());
            lemmasService.decrementFrequencyOrRemoveByIds(previousLemmasIds);

            if (onlyThisPageIndex) removePageFromRepository(relativePath, IndexingService.siteDtoToSite(siteDto));
            else return exit(false, null);
        }

        PageDto pageDto = httpService.getPageDtoFromLink(currentLink);
        pageDto.setPath(relativePath);
        if (relativePath.equals("/") && pageDto.getCode() != HttpStatus.OK.value()) {
            return exit(true, MAIN_PAGE_NOT_AVAILABLE_MESSAGE.formatted(siteDto.getUrl()));
        }

        try {  // проверяем сайт после запроса к нему
            Site site = getSiteEntityOrThrowNoSuchElementException();
            siteDto = IndexingService.siteToSiteDto(site);
        } catch (NoSuchElementException e) {
            return exit(true, e.getLocalizedMessage());
        }

        if (siteDto.getStatus() != SiteStatus.INDEXING) return exit(false, null);

        updateSiteAndPageEntities(pageDto);
        if (pageDto.getCode() < HttpStatus.BAD_REQUEST.value()) {
            indexPage(siteDto, pageDto);  // индексируются только коды < 400
        }
        if (pageDto.getCode() != HttpStatus.OK.value()) return exit(false, null);


        log.info("На странице " + currentLink + " найдено " + pageDto.getLinks().size() + " ссылок");
        Set<String> tasks = new HashSet<>(  // сет для ссылок, которые еще нужно обработать
                getValidAndFormattedLinks(pageDto.getLinks()));  // форматируем и добавляем в сет

        if (!onlyThisPageIndex) {
            List<PageIndexator> taskList = initTaskListFromSet(tasks);
            try {
                joinAllTasks(taskList);
            } catch (RuntimeException e) {
                siteTaskList = new CopyOnWriteArrayList<>();
                log.warn("Была поймана ошибка при обработке дочерних таск! " + e);
            }
        }

        return exit(false, null);
    }

    private Site getSiteEntityOrThrowNoSuchElementException() {
        Optional<Site> optionalSite = siteRepository.findById(siteDto.getId());
        return optionalSite.orElseThrow();
    }

    private CopyOnWriteArraySet<String> exit(boolean isFailed, String errorText) {
        siteTaskList.remove(this);

        try {
            siteDto = IndexingService.siteToSiteDto(getSiteEntityOrThrowNoSuchElementException());
        } catch (NoSuchElementException e) {
            log.error(SITE_NOT_FOUND_MESSAGE.formatted(e.getLocalizedMessage()));
            return new CopyOnWriteArraySet<>();
        }

        String pageIndexationFinishMessage = PROCESS_FINISHED_MESSAGE.formatted(currentLink) +
                ", осталось активных задач по сайту: " + siteTaskList.size();
        if (siteTaskList.isEmpty()) {  // если больше нет задач на индексацию
            if (siteDto.getStatus() != SiteStatus.FAILED) siteDto.setIndexed();
            pageIndexationFinishMessage = SITE_INDEXATION_FINISHED_MESSAGE.formatted(siteDto.getUrl());
        }

        if (isFailed) {
            String errorMessage = errorText != null ?
                    errorText : "[" +siteDto.getUrl() + "] неизвестная ошибка при индексации сайта";
            siteDto.setFailed(errorMessage);
            log.error(errorMessage);
        } else if (siteDto.getStatus() == SiteStatus.INDEXING) {
            siteDto.updateStatusTime();
        }

        siteRepository.saveAndFlush(IndexingService.siteDtoToSite(siteDto));
        log.info(pageIndexationFinishMessage);
        return new CopyOnWriteArraySet<>();
    }

    private Optional<Page> getOptionalPage(String path, Site site) {
        Page searchPage = new Page();
        searchPage.setPath(path);
        searchPage.setSite(site);
        return pageRepository.findAll(Example.of(searchPage)).stream()
                .findFirst();
    }

    private void removePageFromRepository(String relativePath, Site site) {
        Page searchPage = new Page();
        searchPage.setPath(relativePath);
        searchPage.setSite(site);
        Optional<Page> optionalPageToDelete = pageRepository.findAll(Example.of(searchPage)).stream()
                .findFirst();
        optionalPageToDelete.ifPresent(page -> pageRepository.deleteById(page.getId()));
    }

    private void updateSiteAndPageEntities(PageDto pageDto) {
        Site site = updateSite();
        siteDto = IndexingService.siteToSiteDto(site);
        pageDto.setSite(site);
        Optional<Page> optionalPage = Optional.empty();
        if (pageDto.getId() != null) optionalPage = pageRepository.findById(pageDto.getId());
        Page page = optionalPage.orElseGet(() -> pageRepository.saveAndFlush(pageDto.toEntity()));
        pageDto.setId(page.getId());
    }

    private Site updateSite() {
        siteDto.setStatusTime(null);  // для обновления времени статуса
        return siteRepository.saveAndFlush(IndexingService.siteDtoToSite(siteDto));  // обновляем данные сайта
    }

    private void indexPage(SiteDto siteDto, PageDto pageDto) {
        Map<String, Integer> foundLemmas = lemmasService.collectLemmas(pageDto.getContent());
        List<Lemma> lemmaEntitiesToIndex = lemmasService.handleLemmas(foundLemmas, siteDto);
        int count = IndexingService.indexLemmas(indexRepository, lemmaEntitiesToIndex, foundLemmas, pageDto);
        log.info("Проиндексировали " + count + " новых лемм со страницы \"" +
                pageDto.getPath() +"\" сайта "+ siteDto.getUrl());
    }

    private Set<Integer> getPreviousLemmasIdSet(Page page) {
        Index index = new Index();
        index.setPageId(page.getId());
        List<Index> pageIndexes = indexRepository.findAll(Example.of(index));
        return pageIndexes.stream()
                .map(Index::getLemmaId)
                .collect(Collectors.toSet());
    }

    private List<PageIndexator> initTaskListFromSet(Set<String> tasks) {
        List<PageIndexator> taskList = new ArrayList<>();
        for (String linkToProcess : tasks) {  // инициализируем рекурсивные задачи
            PageIndexator task = new PageIndexator(
                    siteDto, linkToProcess,
                    httpService, lemmasService,
                    false);
            task.setSiteTaskList(siteTaskList);
            task.fork();
        }
        return taskList;
    }

    private void joinAllTasks(List<PageIndexator> taskList) {
        siteTaskList.addAll(taskList);
        for (PageIndexator task : taskList) {
                task.join();
        }
    }

    private boolean isValidLink(String linkValue) {
        if (linkValue.length() <= 1 ||
                linkValue.startsWith("javascript") ||
                linkValue.startsWith("tel") ||
                linkValue.startsWith("mailto")) {
            return false;
        }

        if (linkValue.startsWith("http")) {
            return linkValue.contains(currentLink);
        }

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

//        return linkValue.endsWith("/") ? linkValue : linkValue.concat("/");
        return linkValue;
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

            boolean isValid = isValidLink(linkValue);
            if (!isValid) {
                continue;
            }

            String newValue = getFormattedLink(linkValue);
            foundLinks.add(newValue);
        }

        return foundLinks;
    }
}
