package searchengine.services.indexing.utils;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.springframework.data.domain.Example;
import org.springframework.data.repository.Repository;
import org.springframework.http.HttpStatus;
import searchengine.dto.PageDto;
import searchengine.dto.SiteDto;
import searchengine.model.*;
import searchengine.services.HttpJsoupConnectorService;
import searchengine.services.morphology.LemmasService;
import searchengine.services.indexing.IndexingService;
import searchengine.repositories.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RecursiveTask;
import java.util.stream.Collectors;

@Slf4j
@NoArgsConstructor
public class PageIndexator extends RecursiveTask<CopyOnWriteArraySet<String>> {

    public static CopyOnWriteArraySet<String> resultLinks =
            new CopyOnWriteArraySet<>();  // сет для всех найденных адресов (в том числе и недоступных)

    private static final String PROCESS_STARTED_MESSAGE = "Выполняется обработка страницы \"%s\"";
    private static final String PROCESS_FINISHED_MESSAGE = "Закончена обработка страницы \"%s\"";
    private static final String SITE_INDEXATION_FINISHED = "Закончена индексация сайта с url=%s";

    private static Map<String, Repository> repositories;  // для удобной передачи в конструктор потомков

    private HttpJsoupConnectorService httpService;  // сервис запросов к сайту
    private LemmasService lemmasService;  // сервис лемматизации

    private SiteRepository siteRepository;
    private PageRepository pageRepository;
    private IndexRepository indexRepository;

    private SiteDto siteDto;  // инфо сайта
    private String currentLink;  // текущая полная ссылка
    private boolean onlyThisPageIndex;  // флаг для индексации одной конкретной страницы
    private CopyOnWriteArrayList<PageIndexator> siteTaskList;  // для отслеживания оставшихся задач

    /* Конструктор для потомков сайта */
    public PageIndexator(SiteDto siteDto, String currentLink,
                         HttpJsoupConnectorService httpService,
                         Map<String, Repository> repositories,
                         CopyOnWriteArrayList<PageIndexator> siteTaskList,
                         LemmasService lemmasService,
                         boolean onlyThisPageIndex) {
        this.siteDto = siteDto;
        this.currentLink = currentLink;
        this.httpService = httpService;
        PageIndexator.repositories = repositories;
        this.siteRepository = (SiteRepository) repositories.get("siteRepository");
        this.pageRepository = (PageRepository) repositories.get("pageRepository");
        this.indexRepository = (IndexRepository) repositories.get("indexRepository");
        this.siteTaskList = siteTaskList;
        this.lemmasService = lemmasService;
        this.onlyThisPageIndex = onlyThisPageIndex;
    }

    /* Конструктор для корня сайта */
    public PageIndexator(SiteDto siteDto, String siteUrl,
                         Map<String, Repository> repositories,
                         CopyOnWriteArrayList<PageIndexator> siteTaskList,
                         LemmasService lemmasService,
                         boolean onlyThisPageIndex) {
        this(siteDto, siteUrl,
            new HttpJsoupConnectorService(), repositories,
            siteTaskList, lemmasService, onlyThisPageIndex);
    }

    @Override
    protected CopyOnWriteArraySet<String> compute() {
        log.info(PROCESS_STARTED_MESSAGE.formatted(currentLink));
        resultLinks.add(currentLink);  // добавляем в сет всех найденных ссылок
        Site site;
        try {
            site = getSiteOrThrowNoSuchElement();
        } catch (NoSuchElementException e) {
            return errorExit(e);
        }

        String relativePath = currentLink.substring(siteDto.getUrl().length());
        relativePath = relativePath.isEmpty() ? "/" : relativePath;

        Optional<Page> optionalPage = getOptionalPage(relativePath, site);
        boolean isExistsInRepository = optionalPage.isPresent();
        if (isExistsInRepository) {
            Set<Integer> previousLemmasIds = getPreviousLemmasIdSet(optionalPage.get());
            lemmasService.decrementFrequencyOrRemoveByIds(previousLemmasIds);

            if (onlyThisPageIndex) removePageFromRepository(relativePath, site);
        }
        if (siteDto.getStatus() != SiteStatus.INDEXING) return successExit();

        PageDto pageDto = httpService.getPageDtoFromLink(currentLink);
        pageDto.setPath(relativePath);
        if (relativePath.equals("/") && pageDto.getCode() != HttpStatus.OK.value()) {
            mainPageNotAvailableExit(site, pageDto);
            return new CopyOnWriteArraySet<>();
        }

        try {  // проверяем сайт после запроса к нему
            getSiteOrThrowNoSuchElement();
        } catch (NoSuchElementException e) {
            return errorExit(e);
        }

        if (siteDto.getStatus() != SiteStatus.INDEXING) return successExit();

        pageDto = updateSiteAndPageEntities(pageDto);

        if (pageDto.getCode() < HttpStatus.BAD_REQUEST.value()) {
            indexPage(siteDto, pageDto);  // индексируются только коды < 400
        }

        if (pageDto.getCode() != HttpStatus.OK.value()) return successExit();

        log.info("На странице " + currentLink + " найдено " + pageDto.getLinks().size() + " ссылок");
        Set<String> tasks = new HashSet<>(  // сет для ссылок, которые еще нужно обработать
                getValidAndFormattedLinks(pageDto.getLinks()));  // форматируем и добавляем в сет
        tasks.removeAll(resultLinks);  // избавляемся от уже добавленных в сет ссылок

        if (!onlyThisPageIndex) {
            List<PageIndexator> taskList = initTaskListFromSet(tasks);
            joinAllTasks(taskList);
        }

        return successExit();
    }

    private Site getSiteOrThrowNoSuchElement() throws NoSuchElementException {
        siteRepository.flush();
        Optional<Site> optionalSite = siteRepository.findById(siteDto.getId());
        Site site = optionalSite.orElseThrow();  // иначе NoSuchElementException
        siteDto = IndexingService.siteToSiteDto(site);  // записываем рез-т в siteDto
        return site;
    }

    private Optional<Page> getOptionalPage(String path, Site site) {
        Page searchPage = new Page();
        searchPage.setPath(path);
        searchPage.setSite(site);
        return pageRepository.findOne(Example.of(searchPage));
    }

    private void removePageFromRepository(String relativePath, Site site) {
        Page searchPage = new Page();
        searchPage.setPath(relativePath);
        searchPage.setSite(site);
        pageRepository.flush();
        Optional<Page> optionalPageToDelete = pageRepository.findOne(Example.of(searchPage));
        optionalPageToDelete.ifPresent(page -> pageRepository.deleteById(page.getId()));
    }

    private CopyOnWriteArraySet<String> errorExit(Exception e) {
        String message = "Не удалось найти сайт: id=" + siteDto.getId() + ", url=" + siteDto.getUrl() +
                " (" + e.getLocalizedMessage() + ")";
        log.error(message);
        pageIndexingEnd(true, message);
        return new CopyOnWriteArraySet<>();
    }


    private void mainPageNotAvailableExit(Site site, PageDto pageDto) {
        String warnMessage = "Главная страница сайта " + site.getUrl() +
                " вернула " + pageDto.getCode() + " код";
        pageDto.setSite(site);
        pageRepository.saveAndFlush(pageDto.toEntity());
        site.setStatusTime(null);
        site.setStatus(SiteStatus.FAILED);  // тк главная страница недоступна
        site.setLastError(warnMessage);
        siteRepository.saveAndFlush(site);  // обновляем данные сайта
        log.warn(warnMessage);
        log.info(SITE_INDEXATION_FINISHED.formatted(site.getUrl()));
    }

    private CopyOnWriteArraySet<String> successExit() {
        pageIndexingEnd();
        return new CopyOnWriteArraySet<>();
    }

    private PageDto updateSiteAndPageEntities(PageDto pageDto) {
        Site site = updateSite();
        siteDto = IndexingService.siteToSiteDto(site);
        pageDto.setSite(site);
        Page page = pageRepository.saveAndFlush(pageDto.toEntity());
        pageDto.setId(page.getId());
        return pageDto;
    }

    private Site updateSite() {
        siteDto.setStatusTime(null);  // для обновления времени статуса
        return siteRepository.saveAndFlush(
                IndexingService.siteDtoToSite(siteDto));  // обновляем данные сайта
    }

    private void indexPage(SiteDto siteDto, PageDto pageDto) {
        Map<String, Integer> foundLemmas = lemmasService.collectLemmas(pageDto.getContent());
        List<Lemma> lemmaEntitiesToIndex = lemmasService.handleLemmas(foundLemmas, siteDto);
        IndexingService.indexLemmas(indexRepository,
                lemmaEntitiesToIndex, foundLemmas, siteDto, pageDto);
    }

    private Set<Integer> getPreviousLemmasIdSet(Page page) {
        Index index = new Index();
        index.setPageId(page.getId());
        indexRepository.flush();
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
                    httpService, repositories,
                    siteTaskList, lemmasService, false
            );
            task.fork();
            taskList.add(task);
        }
        return taskList;
    }

    private void joinAllTasks(List<PageIndexator> taskList) {
        siteTaskList.addAll(taskList);
        for (PageIndexator task : taskList) {
            task.join();
        }
    }

    private void pageIndexingEnd(boolean isFail, String errorText) {
        siteTaskList.remove(this);
        log.info(PROCESS_FINISHED_MESSAGE.formatted(currentLink) +
                ", осталось активных задач по сайту: " + siteTaskList.size());

        if (siteTaskList.isEmpty()) {
            resultLinks = new CopyOnWriteArraySet<>();  // Сброс ссылок предыдущих индексаций
            Site site = getSiteOrThrowNoSuchElement();
            site.setStatusTime(null);

            if (isFail) site.setStatus(SiteStatus.FAILED);
            if (isFail && errorText!=null) site.setLastError(errorText);
            if (site.getStatus()!=SiteStatus.FAILED) site.setStatus(SiteStatus.INDEXED);

            siteRepository.saveAndFlush(site);  // обновляем данные сайта
            log.info(SITE_INDEXATION_FINISHED.formatted(site.getUrl()));
        }
    }

    private void pageIndexingEnd() {
        pageIndexingEnd(false, null);
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
            // добавляем https://www.{currentLink}, если ссылка относительная
            linkValue = currentLink.concat(linkValue);
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
