package searchengine.services.indexing;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.*;
import searchengine.dto.indexing.*;
import searchengine.model.*;
import searchengine.repositories.*;
import searchengine.services.indexing.exceptions.*;
import searchengine.services.indexing.utils.PageIndexator;
import searchengine.services.morphology.LemmasService;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final ConfigSiteList sites;  // сайты из конфигурационного файла
    private final LemmasService lemmasService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;

    private final Map<String, ForkJoinPool> fjpMap =
            new HashMap<>();  // мапа ForkJoinPool'ов по индексируемым сайтам (url - fjp)

    @PostConstruct
    public void postConstructRoutine() {
        IndexingService.repositories.put("siteRepository", siteRepository);
        IndexingService.repositories.put("pageRepository", pageRepository);
        IndexingService.repositories.put("indexRepository", indexRepository);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Map<String, PageIndexator> prepareIndexingTasks() {
        List<ConfigSite> sitesToProcess = sites.getSites().stream()
                .toList();
        Map<String, PageIndexator> tasks = new HashMap<>();
        for (ConfigSite configSite : sitesToProcess) {
            PageIndexator task = prepareIndexingTask(
                    configSite, configSite.getUrl(), false);
            tasks.put(configSite.getUrl(), task);
        }
        return tasks;
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public PageIndexator preparePageIndexingTask(String queryUrl) {
        ConfigSite configSite = getConfigSiteByUrlOrThrowConfigSiteNotFoundException(queryUrl);
        return prepareIndexingTask(configSite, queryUrl, true);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void submitAll(Map<String, PageIndexator> tasksToSubmit, boolean shouldControlFjp) {
        tasksToSubmit.forEach((url, task) -> {
            ForkJoinPool fjp = new ForkJoinPool();
            fjpMap.put(url, fjp);  // для последующего контроля
            fjp.submit(task);  // процесс не дожидается завершения таски
        });
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void stopAll() {
        List<Site> indexingSites = getIndexingSiteList();

        if (indexingSites.isEmpty()) {
            String message = "Индексация не запущена";
            log.warn(message);
            throw new IndexingIsNotLaunchedException(message);
        }

        indexingSites.forEach(this::stopSiteIndexing);
    }

    /**
     * Метод запуска индексации с параметрами
     * @throws IndexingAlreadyLaunchedException если уже запущена
     * **/
    private PageIndexator prepareIndexingTask(ConfigSite configSite, String url, boolean onlyThisPageIndexing) {
        SiteDto siteDto = initSiteDtoFromRepositoryOrCreateNew(configSite);

        if (onlyThisPageIndexing) log.info("Выполняется индексация/обновление страницы \"" + url + "\"");
        else log.info("Выполняется обработка сайта \"" + configSite.getName() + "\" с url = " + url);

        if (!onlyThisPageIndexing && siteDto.getId() != null) {
            removeSiteFromRepository(siteDto);  // очищаем рез-ты предыдущей индексации сайта
            siteDto.setId(null);
        }

        Site siteEntity = IndexingService.siteDtoToSite(siteDto);
        siteDto = IndexingService.siteToSiteDto(siteRepository.saveAndFlush(siteEntity));
        return initPageIndexatorTask(siteDto, url, onlyThisPageIndexing);
    }

    /**
     * Метод остановки индексации конкретного сайта
     * @throws IndexingIsNotLaunchedException если нет индексируемых сайтов
     */
    private void stopSiteIndexing(Site siteToStop) {
        String url = siteToStop.getUrl();
        if (fjpMap.containsKey(url)) {
            ForkJoinPool fjp = fjpMap.get(url);
            fjp.shutdownNow();

            boolean terminationSuccess = true;
            try {
                terminationSuccess = fjp.awaitTermination(3_000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                log.error("Не удалось дождаться завершения fjp сайта из-за ошибки прерывания: " + e);
            }

            if (!terminationSuccess) {
                log.warn("Не удалось дождаться завершения остановки сайта!");
            }
        }

        String errorText = "Индексация остановлена пользователем";
        SiteDto siteDto = IndexingService.siteToSiteDto(siteToStop);
        siteDto.setFailed(errorText);
        siteRepository.saveAndFlush(IndexingService.siteDtoToSite(siteDto));
        log.info("[" + url + "] " + errorText);
    }

    private Optional<Site> getSiteEntityByConfig(ConfigSite configSite) {
        Site site = new Site();
        String configSiteUrl = configSite.getUrl();
        site.setUrl(configSiteUrl);
        Optional<Site> optionalSite = siteRepository.findOne(Example.of(site));  // поиск сайта по url

        if (optionalSite.isEmpty()) {
            log.info("Не удалось найти сайт с url = " + configSiteUrl + " в БД! Индексация будет запущена впервые.");
        }

        return optionalSite;
    }

    private SiteDto createNewSiteDtoFromConfig(ConfigSite configSite) {
        SiteDto siteDto = new SiteDto();
        siteDto.setName(configSite.getName());
        siteDto.setUrl(configSite.getUrl());
        return siteDto;
    }

    /**
     * Функция получает сайт из БД или создает новый siteDto
     * @throws IndexingAlreadyLaunchedException если уже запущена индексация
     * **/
    private SiteDto initSiteDtoFromRepositoryOrCreateNew(ConfigSite configSite) {
        Optional<Site> optionalSite = getSiteEntityByConfig(configSite);
        SiteDto siteDto = optionalSite.map(IndexingService::siteToSiteDto)
                .orElseGet(() -> createNewSiteDtoFromConfig(configSite));

        if (siteDto.getStatus() == SiteStatus.INDEXING) {  // проверяем индексируется ли сайт
            String message = "Индексация по сайту с url = \"" + siteDto.getUrl() + "\" уже запущена!";
            log.warn(message);
            throw new IndexingAlreadyLaunchedException(message);
        }

        siteDto.setIndexing();
        return siteDto;
    }

    /**
     * Функция получает сайт из конфигурации
     * @throws ConfigSiteNotFoundException если страница находится за пределами сайтов из конфига
     * **/
    private ConfigSite getConfigSiteByUrlOrThrowConfigSiteNotFoundException(String url) {
        List<ConfigSite> configSites = sites.getSites().stream()
                .toList();
        for (ConfigSite configSite : configSites) {
            if (url.contains(configSite.getUrl())) {
                return configSite;
            }
        }
        String message = "Данная страница находится за пределами сайтов," +
                "указанных в конфигурационном файле! Сверьте \"https://\" и \"www.\"" +
                " А также проверьте конфигурационный файл на наличие сайта в нем!";
        log.warn("[" + url + "] " + message);
        throw new ConfigSiteNotFoundException(message);
    }

    /**
     * Метод для очистки прошлых записей индексации сайта
     * **/
    private void removeSiteFromRepository(SiteDto siteDto) {
        Site site = new Site();
        site.setUrl(siteDto.getUrl());
        Example<Site> siteExample = Example.of(site);
        List<Site> foundPages = siteRepository.findAll(siteExample);
        siteRepository.deleteAll(foundPages);  // очищаем прошлые записи индексации страниц сайта
        siteRepository.flush();
    }

    private PageIndexator initPageIndexatorTask(SiteDto siteDto, String url, boolean onlyThisPageIndexing) {
        PageIndexator indexator = new PageIndexator(siteDto, url, lemmasService, onlyThisPageIndexing);
        indexator.setSiteTaskList(new CopyOnWriteArrayList<>());
        return indexator;
    }

    private List<Site> getIndexingSiteList() {
        Site siteToFound = new Site();
        siteToFound.setStatus(SiteStatus.INDEXING);
        return siteRepository.findAll(Example.of(siteToFound));
    }

}
