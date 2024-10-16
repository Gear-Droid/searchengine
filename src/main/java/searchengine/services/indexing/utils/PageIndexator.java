package searchengine.services.indexing.utils;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.springframework.data.domain.Example;
import org.springframework.http.HttpStatus;
import searchengine.dto.PageDto;
import searchengine.dto.SiteDto;
import searchengine.model.Page;
import searchengine.model.Site;
import searchengine.model.SiteStatus;
import searchengine.services.indexing.IndexingService;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RecursiveTask;

@Slf4j
@NoArgsConstructor
public class PageIndexator extends RecursiveTask<CopyOnWriteArraySet<String>> {

    public static CopyOnWriteArraySet<String> resultLinks;  // сет для всех найденных адресов (в том числе и недоступных)

    private static final String PROCESS_STARTED_MESSAGE = "Выполняется обработка страницы \"%s\"";
    private static final String PROCESS_FINISHED_MESSAGE = "Закончена обработка страницы \"%s\"";

    private SiteDto siteDto;
    private String currentLink;
    private HttpJsoupConnector httpService;  // сервис запросов к сайту
    private SiteRepository siteRepository;
    private PageRepository pageRepository;
    private CopyOnWriteArrayList<PageIndexator> siteTaskList;

    /* Конструктор для потомков корня сайта */
    public PageIndexator(SiteDto siteDto,
                         String currentLink,
                         HttpJsoupConnector httpService,
                         SiteRepository siteRepository,
                         PageRepository pageRepository,
                         CopyOnWriteArrayList<PageIndexator> siteTaskList) {
        this.siteDto = siteDto;
        this.currentLink = currentLink;
        this.httpService = httpService;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
        this.siteTaskList = siteTaskList;
    }

    /* Конструктор для корня сайта */
    public PageIndexator(
            SiteDto siteDto,
            String siteUrl,
            SiteRepository siteRepository,
            PageRepository pageRepository,
            CopyOnWriteArrayList<PageIndexator> siteTaskList) {
        this(
            siteDto,
            siteUrl,
            new HttpJsoupConnector(),
            siteRepository,
            pageRepository,
            siteTaskList
        );
    }

    @Override
    protected CopyOnWriteArraySet<String> compute() {
        log.info(PROCESS_STARTED_MESSAGE.formatted(currentLink));
        resultLinks.add(currentLink);  // добавляем в сет всех найденных ссылок
        Site site;
        try {
            site = getSiteOrThrowNoSuchElement();
        } catch (NoSuchElementException e) {
            log.error("Не удалось найти сайт: id=" + siteDto.getId() + ", url=" + siteDto.getUrl() +
                    " (" + e.getMessage() + ")");
            pageIndexingEnd();
            return new CopyOnWriteArraySet<>();
        }

        String relativePath = currentLink.substring(siteDto.getUrl().length());
        relativePath = relativePath.isEmpty() ? "/" : relativePath;
        boolean isExistsInRepository = isExistsInRepository(relativePath, site);
        if (siteDto.getStatus() != SiteStatus.INDEXING || isExistsInRepository) {
            pageIndexingEnd();
            return new CopyOnWriteArraySet<>();
        }

        PageDto pageDto = httpService.getPageDtoFromLink(currentLink);
        pageDto.setPath(relativePath);
        try {  // проверяем сайт после запроса к сайту
            getSiteOrThrowNoSuchElement();
            if (siteDto.getStatus() != SiteStatus.INDEXING) {
                throw new NoSuchElementException();  // принудительное завершение
            }
        } catch (NoSuchElementException e ) {
            pageIndexingEnd();
            return new CopyOnWriteArraySet<>();
        }
        updateSiteAndPageEntities(pageDto);
        if (pageDto.getCode() != HttpStatus.OK.value()) {
            pageIndexingEnd();
            return new CopyOnWriteArraySet<>();  // если страница вернула код != ОК
        }

        log.info("На странице " + currentLink + " найдено " + pageDto.getLinks().size() + " ссылок");
        // сет для ссылок, которые еще нужно обработать
        Set<String> tasks = new HashSet<>(
                getValidAndFormattedLinks(pageDto.getLinks()));  // форматируем и добавляем в сет
        tasks.removeAll(resultLinks);  // избавляемся от уже добавленных в сет ссылок

        List<PageIndexator> taskList = initTaskListFromSet(tasks);
        joinAllTasks(taskList);

        pageIndexingEnd();
        return resultLinks;
    }

    private Site getSiteOrThrowNoSuchElement() throws NoSuchElementException {
        siteRepository.flush();
        Optional<Site> optionalSite = siteRepository.findById(siteDto.getId());
        Site site = optionalSite.orElseThrow();  // иначе NoSuchElementException
        siteDto = IndexingService.siteToSiteDto(site);  // записываем рез-т в siteDto
        return site;
    }

    private boolean isExistsInRepository(String path, Site site) {
        Page searchPage = new Page();
        searchPage.setPath(path);
        searchPage.setSite(site);
        return pageRepository.exists(Example.of(searchPage));
    }

    private void updateSiteAndPageEntities(PageDto pageDto) {
        Site site = updateSite();
        pageDto.setSite(site);
        pageRepository.saveAndFlush(pageDto.toEntity());
    }

    private Site updateSite() {
        siteDto.setStatusTime(null);  // для обновления времени статуса
        return siteRepository.saveAndFlush(
                IndexingService.siteDtoToSite(siteDto));  // обновляем данные сайта
    }

    private List<PageIndexator> initTaskListFromSet(Set<String> tasks) {
        List<PageIndexator> taskList = new ArrayList<>();
        for (String linkToProcess : tasks) {
            // инициализируем рекурсивные задачи
            PageIndexator task = new PageIndexator(
                    siteDto, linkToProcess, httpService,
                    siteRepository, pageRepository,
                    siteTaskList
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

    void pageIndexingEnd() {
        siteTaskList.remove(this);
        log.info(PROCESS_FINISHED_MESSAGE.formatted(currentLink) +
                ", осталось активных задач по сайту: " + siteTaskList.size());
        if (siteTaskList.isEmpty()) {
            Site site = getSiteOrThrowNoSuchElement();
            site.setStatus(SiteStatus.INDEXED);
            siteRepository.saveAndFlush(site);  // обновляем данные сайта
            log.info("Закончена индексация сайта с url=" + site.getUrl());
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
            // добавляем https://www.{root}, если ссылка относительная
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
