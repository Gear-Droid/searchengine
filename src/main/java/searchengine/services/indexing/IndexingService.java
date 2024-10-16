package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SitesList;
import searchengine.dto.SiteDto;
import searchengine.model.Site;
import searchengine.model.SiteStatus;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.indexing.exceptions.IndexingAlreadyLaunchedException;
import searchengine.services.indexing.exceptions.IndexingIsNotLaunchedException;
import searchengine.services.indexing.utils.PageIndexator;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingService {

    private final SitesList sites;  // сайты из конфигурационного файла
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    private final Map<searchengine.config.Site, ForkJoinPool> fjpMap =
            new HashMap<>();  // мапа ForkJoinPool'ов по индексируемым сайтам

    /**
     * Метод обхода сайтов из конфигурационного файла, и вызова их обработки
     * **/
    public void indexAll() throws IndexingAlreadyLaunchedException {
        List<searchengine.config.Site> sitesToProcess = sites.getSites().stream()
                .toList();
        PageIndexator.resultLinks = new CopyOnWriteArraySet<>();  // Сброс ссылок предыдущих индексаций
        sitesToProcess.forEach(this::startSiteIndexing);
    }

    /**
     * Метод запуска индексации по определенному сайту
     * **/
    @Transactional
    private void startSiteIndexing(searchengine.config.Site configSite) throws IndexingAlreadyLaunchedException {
        String configSiteName = configSite.getName();
        String configSiteUrl = configSite.getUrl();
        log.info("Выполняется обработка сайта \"" + configSiteName + "\" с url = " + configSiteUrl);

        SiteDto siteDto = getSiteDto(configSite);
        if (siteDto.getStatus() == SiteStatus.INDEXING) {  // проверяем индексируется ли сайт сейчас
            String message = "Индексация по сайту с url = \"" + configSiteUrl + "\" уже запущена!";
            log.warn(message);
            throw new IndexingAlreadyLaunchedException(message);
        }

        prepareRepositories(siteDto);  // очищаем рез-ты предыдущей индексации

        siteDto.setStatus(SiteStatus.INDEXING);
        siteDto.setStatusTime(null);  // для обновления времени статуса
        siteDto = siteToSiteDto(
                siteRepository.saveAndFlush(siteDtoToSite(siteDto)));  // обновляем данные сайта

        ForkJoinPool fjp = new ForkJoinPool();  // запускаем обход страниц сайта
        CopyOnWriteArrayList<PageIndexator> siteTasksList = new CopyOnWriteArrayList<>();
        PageIndexator task =
                new PageIndexator(siteDto, siteDto.getUrl(), siteRepository, pageRepository, siteTasksList);
        siteTasksList.add(task);
        fjpMap.put(configSite, fjp);

        /* TODO: попробовать try-with-resources с FJP */
        fjp.submit(task);  // не дожидается конца индексирования

//        fjp.invoke(task);  // дожидается конца индексирования
//        site.setStatus(SiteStatus.INDEXED);
//        siteRepository.saveAndFlush(site);  // обновляем данные индексации сайта
//        log.info("Закончена обработка сайта \"" + configSiteName + "\" с url = " + configSiteUrl);
    }

    /*
    * Метод получения существующего SiteDto из БД или создания нового
    * **/
    private SiteDto getSiteDto(searchengine.config.Site configSite) {
        String configSiteUrl = configSite.getUrl();
        Site site = new Site();
        site.setUrl(configSiteUrl);

        Optional<Site> optionalSite = siteRepository.findOne(Example.of(site));  // поиск сайта по url

        SiteDto siteDto = new SiteDto();
        if (optionalSite.isPresent()) {
            siteDto = siteToSiteDto(optionalSite.get());
        } else {
            log.info("Не удалось найти сайт с url = " + configSiteUrl + " в БД! Индексация будет запущена впервые.");
            siteDto.setUrl(configSiteUrl);
        }

        siteDto.setName(configSite.getName());
        return siteDto;
    }

    /**
     * Метод для очистки прошлых записей индексации
     * **/
    private void prepareRepositories(SiteDto siteDto) {
        Site site = new Site();
        site.setUrl(siteDto.getUrl());
        Example<Site> siteExample = Example.of(site);
        List<Site> foundPages = siteRepository.findAll(siteExample);
        siteRepository.deleteAll(foundPages);  // очищаем прошлые записи индексации страниц сайта
        siteRepository.flush();
    }

    /**
     * Метод остановки индексации сайтов
     * **/
    @Transactional
    public void stopAll() throws IndexingIsNotLaunchedException {
        Site siteToFound = new Site();
        siteToFound.setStatus(SiteStatus.INDEXING);
        List<Site> indexingSites = siteRepository.findAll(Example.of(siteToFound));

        if (indexingSites.isEmpty()) {
            String message = "Индексация не запущена!";
            log.warn(message);
            throw new IndexingIsNotLaunchedException(message);
        }

        indexingSites.forEach(this::stopSiteIndexing);
    }

    /**
     * Метод остановки индексации конкретного сайта
     * **/
    public void stopSiteIndexing(Site siteToStop) {
        int id = siteToStop.getId();
        Optional<Site> optionalSite = siteRepository.findById(id);
        if (optionalSite.isEmpty()) {
            log.error("Не удалось найти сайт с id = " + id + " в БД!");
            return;
        }
        if (fjpMap.containsKey(siteToStop)) {
            ForkJoinPool fjp = fjpMap.get(siteToStop);
            fjp.shutdownNow();
            try {
                fjp.awaitTermination(5_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                log.error("Не удалось дождаться завершения fjp сайта из-за прерывания " + siteToStop);
            }
        }

        Site site = optionalSite.get();
        site.setStatus(SiteStatus.FAILED);
        site.setLastError("Индексация остановлена пользователем");
        siteRepository.saveAndFlush(site);

        log.info("Индексация по сайту \"" + siteToStop.getName() + "\" с url = " +
                siteToStop.getUrl() + " остановлена пользователем");
    }

    public void indexPage(String url) {
        log.info(url);
    }

    public static SiteDto siteToSiteDto(Site site) {
        SiteDto siteDto = new SiteDto();
        siteDto.setId(site.getId());
        siteDto.setStatus(site.getStatus());
        siteDto.setStatusTime(site.getStatusTime());
        siteDto.setLastError(site.getLastError());
        siteDto.setUrl(site.getUrl());
        siteDto.setName(site.getName());
        return siteDto;
    }

    public static Site siteDtoToSite(SiteDto siteDto) {
        Site site = new Site();
        site.setId(siteDto.getId());
        site.setStatus(siteDto.getStatus());
        site.setStatusTime(siteDto.getStatusTime());
        site.setLastError(siteDto.getLastError());
        site.setUrl(siteDto.getUrl());
        site.setName(siteDto.getName());
        return site;
    }
}
