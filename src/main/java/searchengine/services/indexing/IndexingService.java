package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SitesList;
import searchengine.model.Site;
import searchengine.model.SiteStatus;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    private final SitesList sites;  // сайты из конфигурационного файла
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    private final Map<Site, List<ForkJoinPool>> fjpsMap = new HashMap<>();  // мапа ForkJoinPool'ов по индексируемым сайтам

    /**
     * Метод обхода сайтов из конфигурационного файла, и вызова их обработки
     * **/
    @Transactional
    public void indexPagesFromSitesList() throws IndexingAlreadyLaunchedException {
        List<searchengine.config.Site> sitesToProcess = sites.getSites().stream()
                .toList();
        sitesToProcess.forEach(this::handleSite);
    }

    /**
     * Метод запуска индексации по определенному сайту
     * **/
    public void handleSite(searchengine.config.Site configSite) throws IndexingAlreadyLaunchedException {
        String configSiteName = configSite.getName();
        String configSiteUrl = configSite.getUrl();
        log.info("Выполняется обработка сайта \"" + configSiteName + "\" с url = " + configSiteUrl);

        Site site = new Site();
        site.setUrl(configSiteUrl);
        checkAndPrepareSiteRepository(site);  // проверяем индексируется ли сайт сейчас, очищаем если нет

        site.setName(configSiteName);
        site.setStatus(SiteStatus.INDEXING);
        siteRepository.saveAndFlush(site);  // обновляем данные индексации сайта

        RecursiveTask<CopyOnWriteArraySet<String>> task = new SiteIndexator(site, siteRepository, pageRepository);

        /* TODO: попробовать try-with-resources с FJP */
        ForkJoinPool fjp = new ForkJoinPool();  // запускаем обход страниц сайта

//        fjp.invoke(task);  // дожидается конца индексирования
        fjp.submit(task);  // не дожидается конца индексирования

//        site.setStatus(SiteStatus.INDEXED);
//        siteRepository.saveAndFlush(site);  // обновляем данные индексации сайта
//        log.info("Закончена обработка сайта \"" + configSiteName + "\" с url = " + configSiteUrl);
    }

    private void checkAndPrepareSiteRepository(Site site) throws IndexingAlreadyLaunchedException {
        List<Site> foundSites = siteRepository.findAll(Example.of(site));  // поиск сайтов по url
        foundSites.forEach((foundSite) -> {  // проверяем в процессе ли индексация данного сайта
            if (foundSite.getStatus() == SiteStatus.INDEXING) {
                String message = "Индексация по сайту с url = \"" + site.getUrl() + "\" уже запущена!";
                log.info(message);
                throw new IndexingAlreadyLaunchedException(message);
            }
        });
        siteRepository.deleteAll(foundSites);  // очищаем прошлые записи индексации сайта
        siteRepository.flush();
    }

    public void indexPage(String url) {
        log.info(url);
    }
}
