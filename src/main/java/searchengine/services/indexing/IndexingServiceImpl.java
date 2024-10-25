package searchengine.services.indexing;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Example;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.ConfigSite;
import searchengine.config.ConfigSiteList;
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
import java.util.stream.Collectors;

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

    @Override
    @Transactional
    public void indexAll() {
        List<ConfigSite> sitesToProcess = sites.getSites().stream()
                .toList();
        Map<String, PageIndexator> tasksToSubmit;
        tasksToSubmit = sitesToProcess.stream()
                .collect(Collectors.toMap(ConfigSite::getUrl, this::startSiteIndexing));

        tasksToSubmit.forEach((url, task) -> {
            ForkJoinPool fjp = new ForkJoinPool();
            fjpMap.put(url, fjp);  // для последующего контроля
            fjp.submit(task);  // процесс не дожидается завершения таски
        });
    }

    /**
     * Метод запуска индексации по определенному сайту
     * @throws IndexingAlreadyLaunchedException если уже запущена
     * **/
    private PageIndexator startSiteIndexing(ConfigSite configSite) {
        SiteDto siteDto = initSiteDtoFromRepositoryOrCreateNew(configSite);
        String url = siteDto.getUrl();
        log.info("Выполняется обработка сайта \"" + configSite.getName() + "\" с url = " + url);

        if (siteDto.getId() != null) {
            removeSiteFromRepository(siteDto);  // очищаем рез-ты предыдущей индексации
            siteDto.setId(null);
        }

        siteDto = IndexingService.siteToSiteDto(transactionalSiteSave(siteDto));
        return initPageIndexatorTask(siteDto, url, false);
    }

    @Override
    @Transactional
    public void indexPage(String queryUrl) {
        ConfigSite configSite = getConfigSiteByUrlOrThrowConfigSiteNotFoundException(queryUrl);

        SiteDto siteDto = initSiteDtoFromRepositoryOrCreateNew(configSite);
        String url = siteDto.getUrl();
        log.info("Выполняется индексация/обновление страницы \"" + url + "\"");

        siteDto = IndexingService.siteToSiteDto(
                siteRepository.saveAndFlush(IndexingService.siteDtoToSite(siteDto))  // обновляем
        );

        ForkJoinPool fjp = new ForkJoinPool();
        PageIndexator task = initPageIndexatorTask(siteDto, url, true);
        fjp.submit(task);  // процесс не дожидается завершения таски
    }

    @Override
    @Transactional
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
     * Метод для очистки прошлых записей индексации
     * **/
    private void removeSiteFromRepository(SiteDto siteDto) {
        Site site = new Site();
        site.setUrl(siteDto.getUrl());
        Example<Site> siteExample = Example.of(site);
        List<Site> foundPages = siteRepository.findAll(siteExample);
        siteRepository.deleteAll(foundPages);  // очищаем прошлые записи индексации страниц сайта
        siteRepository.flush();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    private Site transactionalSiteSave(SiteDto siteDto) {
        return siteRepository.saveAndFlush(IndexingService.siteDtoToSite(siteDto));
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
