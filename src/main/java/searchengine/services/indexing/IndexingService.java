package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SitesList;
import searchengine.model.Site;
import searchengine.model.SiteStatus;
import searchengine.repositories.SiteRepository;

import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    private static final String SERVICE_ACTION_TEMPLATE =
            "Сервис " + IndexingService.class.getName() + ": %s";

    private final SitesList sites;  // сайты из конфигурационного файла
    private final SiteRepository siteRepository;

    @Transactional
    public void indexPagesFromSitesList() throws IndexingAlreadyLaunchedException {
        List<searchengine.config.Site> sitesToProcess = sites.getSites().stream()
                .toList();
        sitesToProcess.forEach(this::handleSite);
    }

    public void handleSite(searchengine.config.Site configSite) throws IndexingAlreadyLaunchedException {
        String configSiteName = configSite.getName();
        String configSiteUrl = configSite.getUrl();
        log.info(SERVICE_ACTION_TEMPLATE.formatted(
                "Выполняется обработка сайта \"" + configSiteName + "\" с url = " + configSiteUrl));

        Site site = new Site();
        site.setUrl(configSiteUrl);
        checkAndPrepareSiteRepository(site);  // проверяем индексируется ли сайт сейчас, очищаем если нет

        site.setName(configSiteName);
        site.setStatus(SiteStatus.INDEXING);
        siteRepository.save(site);  // обновляем данные индексации сайта

//        RecursiveTask<CopyOnWriteArraySet<String>> task =
//                new PagesTreeBuilder(site, site.getUrl());
//        new ForkJoinPool().invoke(task);  // запускаем обход сайта
    }

    private void checkAndPrepareSiteRepository (Site site) throws IndexingAlreadyLaunchedException {
        List<Site> foundSites = siteRepository.findAll(Example.of(site));  // поиск сайтов по url
        foundSites.forEach((foundSite) -> {  // проверяем в процессе ли индексация данного сайта
            if (foundSite.getStatus() == SiteStatus.INDEXING) {
                String message = SERVICE_ACTION_TEMPLATE.formatted(
                        "Индексация по сайту с url = \"" + site.getUrl() + "\" уже запущена!");
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
