package searchengine.services.indexing;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.springframework.http.HttpStatus;
import searchengine.dto.PageDto;
import searchengine.model.Site;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RecursiveTask;

@Slf4j
@NoArgsConstructor
public class SiteIndexator extends RecursiveTask<CopyOnWriteArraySet<String>> {

    private static final CopyOnWriteArraySet<String> resultPages =
            new CopyOnWriteArraySet<>();  // сет для всех найденных адресов (в том числе и недоступных)

    private Site site;
    private String currentLink;
    private HttpJsoupConnector httpService;  // сервис запросов к сайту
    private SiteRepository siteRepository;
    private PageRepository pageRepository;

    /* Конструктор для потомков корня сайта */
    public SiteIndexator(Site site, String linkToProcess,
                         HttpJsoupConnector httpService,
                         SiteRepository siteRepository,
                         PageRepository pageRepository) {
        this.site = site;
        currentLink = linkToProcess;
        this.httpService = httpService;
        this.siteRepository = siteRepository;
        this.pageRepository = pageRepository;
    }

    /* Конструктор для корня сайта */
    public SiteIndexator(
            Site site,
            SiteRepository siteRepository,
            PageRepository pageRepository) {
        this(
            site, site.getUrl(),
            new HttpJsoupConnector(),
            siteRepository,
            pageRepository
        );
    }

    @Override
    protected CopyOnWriteArraySet<String> compute() {
        resultPages.add(currentLink);  // добавляем в сет всех найденных ссылок
        PageDto pageDto = httpService.getPageDtoFromLink(currentLink);
        System.out.println(pageDto.getCode());
        updatePageAndSiteEntities(pageDto);
        if (pageDto.getCode() != HttpStatus.OK.value()) {
            return new CopyOnWriteArraySet<>();  // если страница вернула код != ОК
        }

        Set<String> tasks = new HashSet<>();  // сет для ссылок, которые еще нужно обработать
        tasks.addAll(getValidAndFormattedLinks(pageDto.getLinks()));  // форматируем и добавляем в сет
        tasks.removeAll(resultPages);  // избавляемся от уже добавленных в сет ссылок

        List<SiteIndexator> taskList = new ArrayList<>();
        for (String linkToProcess : tasks) {
            // инициализируем рекурсивные задачи
            SiteIndexator task = new SiteIndexator(
                    site, linkToProcess,
                    httpService, siteRepository, pageRepository
            );
            task.fork();
            taskList.add(task);
        }
        for (SiteIndexator task : taskList) {
            task.join();
        }
        return resultPages;
    }

    private void updatePageAndSiteEntities(PageDto pageDto) {
        site.setStatusTime(LocalDateTime.now());
        siteRepository.saveAndFlush(site);  // обновляем данные индексации сайта
        pageDto.setSite(site);
        pageRepository.saveAndFlush(pageDto.toEntity());
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
