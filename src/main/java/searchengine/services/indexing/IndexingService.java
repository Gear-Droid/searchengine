package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.SitesList;
import searchengine.dto.PageDto;
import searchengine.dto.SiteDto;
import searchengine.model.*;
import searchengine.repositories.*;
import searchengine.services.indexing.exceptions.*;
import searchengine.services.indexing.utils.PageIndexator;
import searchengine.services.morphology.LemmasService;

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
    private final LemmasService lemmasService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;

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
    private void startSiteIndexing(searchengine.config.Site configSite)
            throws IndexingAlreadyLaunchedException {
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
        siteDto = updateSiteAndGetDto(siteDto);  // обновляем siteDto

        ForkJoinPool fjp = new ForkJoinPool();  // запускаем обход страниц сайта
        PageIndexator task = initPageIndexatorTask(siteDto, siteDto.getUrl(), false);
        fjpMap.put(configSite, fjp);  // для последующего контроля
        fjp.submit(task);  // не дожидается конца индексирования
    }

    /**
     * Метод остановки индексации сайтов
     * **/
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
    @Transactional
    private void stopSiteIndexing(Site siteToStop) {
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

    @Transactional
    public void indexPage(String url) throws ConfigSiteNotFoundException,
            IndexingAlreadyLaunchedException {
        searchengine.config.Site configSite = getConfigSiteOrThrowNotFound(url);
        SiteDto siteDto = getSiteDto(configSite);

        if (siteDto.getStatus() == SiteStatus.INDEXING) {  // проверяем индексируется ли сайт сейчас
            String message = "Индексация по сайту с url = \"" + configSite.getUrl() + "\" уже запущена!";
            log.warn(message);
            throw new IndexingAlreadyLaunchedException(message);
        }

        siteDto = updateSiteAndGetDto(siteDto);  // обновляем siteDto

        ForkJoinPool fjp = new ForkJoinPool();
        PageIndexator task = initPageIndexatorTask(siteDto, url, true);
        fjpMap.put(configSite, fjp);  // для последующего контроля
        fjp.submit(task);  // не дожидается конца индексирования
    }

    /*
    * Метод получения существующего SiteDto из БД или создания нового
    * **/
    private SiteDto getSiteDto(searchengine.config.Site configSite) {
        String configSiteUrl = configSite.getUrl();
        Site site = new Site();
        site.setUrl(configSiteUrl);

        Optional<Site> optionalSite = siteRepository
                .findOne(Example.of(site));  // поиск сайта по url

        SiteDto siteDto = new SiteDto();
        if (optionalSite.isPresent()) {
            siteDto = siteToSiteDto(optionalSite.get());
        } else {
            log.info("Не удалось найти сайт с url = " + configSiteUrl + " в БД!" +
                    " Индексация будет запущена впервые.");
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

    private SiteDto updateSiteAndGetDto(SiteDto siteDto) {
        siteDto.setStatusTime(null);  // для обновления времени статуса
        siteDto.setStatus(SiteStatus.INDEXING);
        return siteToSiteDto(
                siteRepository.saveAndFlush(siteDtoToSite(siteDto)));  // обновляем данные сайта
    }

    private PageIndexator initPageIndexatorTask(
            SiteDto siteDto, String url, boolean onlyThisPageIndex) {
        CopyOnWriteArrayList<PageIndexator> siteTasksList = new CopyOnWriteArrayList<>();
        HashMap<String, Repository> repositories = new HashMap<>();
        repositories.put("siteRepository", siteRepository);
        repositories.put("pageRepository", pageRepository);
        repositories.put("indexRepository", indexRepository);
        PageIndexator task = new PageIndexator(
                siteDto, url, repositories,
                siteTasksList, lemmasService,
                onlyThisPageIndex);
        siteTasksList.add(task);
        return task;
    }

    private searchengine.config.Site getConfigSiteOrThrowNotFound(String url)
            throws ConfigSiteNotFoundException {
        List<searchengine.config.Site> configSites = sites.getSites().stream()
                .toList();
        for (searchengine.config.Site configSite : configSites) {
            if (url.contains(configSite.getUrl())) {
                return configSite;
            }
        }
        String message = "Данная страница находится за пределами сайтов," +
                "указанных в конфигурационном файле!";
        log.warn("[" + url + "] " + message);
        throw new ConfigSiteNotFoundException(message);
    }

    /**
     * @param lemmasToIndex список объектов Lemma для индексации
     * @param pageLemmasCount key-value мапа текущей страницы: "лемма" - "кол-во на странице"
     * @param pageDto Dto с инфой страницы
     */
    @Transactional
    public static void indexLemmas(IndexRepository indexRepository,
                                   List<Lemma> lemmasToIndex,
                                   Map<String, Integer> pageLemmasCount,
                                   SiteDto siteDto,
                                   PageDto pageDto) {
        List<Index> indexToSaveList = new ArrayList<>();
        Integer pageId = pageDto.getId();
        for (Lemma lemma : lemmasToIndex) {
            Index index = new Index();
            index.setLemmaId(lemma.getId());
            String lemmaValue = lemma.getLemma();

            if (!pageLemmasCount.containsKey(lemmaValue)) {
                log.warn("Не удалось найти лемму \"" + lemmaValue + "\" в " + pageLemmasCount);
                continue;
            }

            Integer lemmaCount = pageLemmasCount.get(lemmaValue);
            float rank = lemmaCount.floatValue();
            index.setRank(rank);
            index.setPageId(pageId);
            indexToSaveList.add(index);
        }

        indexRepository.saveAllAndFlush(indexToSaveList);
        log.info("Проиндексировали " + indexToSaveList.size() + " новые леммы со страницы \"" +
                pageDto.getPath() +"\" сайта "+ siteDto.getUrl());
    }

    @Transactional
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

    @Transactional
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
