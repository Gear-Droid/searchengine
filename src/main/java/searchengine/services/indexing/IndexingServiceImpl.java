package searchengine.services.indexing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.*;
import searchengine.dto.indexing.*;
import searchengine.exceptions.ConfigSiteNotFoundException;
import searchengine.exceptions.IndexingAlreadyLaunchedException;
import searchengine.exceptions.IndexingIsNotLaunchedException;
import searchengine.mappers.SiteMapper;
import searchengine.model.*;
import searchengine.repositories.*;
import searchengine.services.indexing.utils.PageIndexator;
import searchengine.services.morphology.LemmasService;

import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private final LemmasService lemmasService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final IndexRepository indexRepository;
    private final ConfigSiteList configSites;  // сайты из конфигурационного файла

    private ForkJoinPool fjp = new ForkJoinPool();  // ForkJoinPool для контроля за индексируемыми сайтами

    @Override
    @Transactional
    public void removeUnusedSites() {
        siteRepository.deleteAll(getUnusedSites());
    }

    @Override
    @Transactional
    public List<PageIndexator> initSitesIndexingTasks() {
        try {
            return configSites.getSites().stream()
                    .map(this::initIndexingTask)
                    .toList();
        } catch (IndexingAlreadyLaunchedException e) {
            throw new IndexingAlreadyLaunchedException("Индексация уже запущена");
        }
    }

    @Override
    @Transactional
    public PageIndexator initPageIndexingTask(String queryUrl) {
        ConfigSite configSite = getConfigSiteByUrlOrThrowConfigSiteNotFoundException(queryUrl);
        log.info("Выполняется индексация/обновление страницы \"" + queryUrl + "\"");
        return initIndexingTask(configSite, queryUrl, true);
    }

    @Override
    @Transactional
    public void submitAll(List<PageIndexator> tasksToSubmit) {
        fjp = new ForkJoinPool();
        tasksToSubmit.forEach(fjp::submit);  // процесс не дожидается завершения таски
    }

    @Override
    @Transactional
    public void stopAll() {
        List<Site> indexingSites = siteRepository.findAllByStatus(SiteStatus.INDEXING);
        if (indexingSites.isEmpty()) throw new IndexingIsNotLaunchedException("Индексация не запущена");
        indexingSites.forEach(this::stopSiteIndexing);
        fjp.shutdownNow();
    }

    private List<Site> getUnusedSites() {
        Set<String> configSitesUrl = configSites.getSites().stream()
                .map(ConfigSite::getUrl)
                .collect(Collectors.toSet());
        return siteRepository.findAll().stream()
                .filter(site -> !configSitesUrl.contains(site.getUrl()))
                .toList();
    }

    private ConfigSite getConfigSiteByUrlOrThrowConfigSiteNotFoundException(String url) {
        return configSites.getSites().stream()
                .filter(configSite -> url.contains(configSite.getUrl()))
                .findFirst()
                .orElseThrow(() -> new ConfigSiteNotFoundException("Данная страница находится за пределами сайтов," +
                        " указанных в конфигурационном файле! Сверьте \"https://\" и \"www.\"" +
                        " А также проверьте конфигурационный файл на наличие сайта в нем!"));
    }

    private PageIndexator initIndexingTask(ConfigSite configSite) {
        log.info("Выполняется обработка сайта \"" + configSite.getName() + "\" с url = " + configSite.getUrl());
        return initIndexingTask(configSite, configSite.getUrl(), false);
    }

    private Optional<Site> getSiteEntityByConfig(ConfigSite configSite) {
        Optional<Site> optionalSite = siteRepository.findOneByUrl(configSite.getUrl());  // поиск сайта по url
        if (optionalSite.isEmpty()) {
            log.info("Не удалось найти сайт с url = " + configSite.getUrl() + " в БД! Индексация запущена впервые.");
        }
        return optionalSite;
    }

    private SiteDto createNewSiteDtoFromConfig(ConfigSite configSite) {
        SiteDto siteDto = new SiteDto();
        siteDto.setName(configSite.getName());
        siteDto.setUrl(configSite.getUrl());
        return siteDto;
    }

    private SiteDto initSiteDtoFromRepositoryOrCreateNew(ConfigSite configSite) {
        SiteDto siteDto = getSiteEntityByConfig(configSite)
                .map(SiteMapper.INSTANCE::siteToSiteDto)
                .orElseGet(() -> createNewSiteDtoFromConfig(configSite));

        if (siteDto.getStatus() == SiteStatus.INDEXING) {  // проверяем индексируется ли сайт
            String message = "Индексация по сайту с url = \"" + siteDto.getUrl() + "\" уже запущена!";
            log.warn(message);
            throw new IndexingAlreadyLaunchedException(message);
        }

        siteDto.setIndexing();
        return siteDto;
    }

    private void removeSiteFromRepository(SiteDto siteDto) {
        List<Site> foundPages = siteRepository.findAllByUrl(siteDto.getUrl());
        siteRepository.deleteAll(foundPages);  // очищаем прошлые записи индексации страниц сайта
        siteRepository.flush();
    }

    private PageIndexator initIndexingTask(ConfigSite configSite, String url, boolean onlyThisPageIndexing) {
        SiteDto siteDto = initSiteDtoFromRepositoryOrCreateNew(configSite);

        if (!onlyThisPageIndexing && siteDto.getId() != null) {
            removeSiteFromRepository(siteDto);
            siteDto.setId(null);
        }

        siteDto.updateStatusTime();
        Site siteEntity = SiteMapper.INSTANCE.siteDtoToSite(siteDto);
        siteDto = SiteMapper.INSTANCE.siteToSiteDto(siteRepository.saveAndFlush(siteEntity));

        return new PageIndexator(siteDto, url.endsWith("/") ? url : url.concat("/"), this,
                lemmasService, siteRepository, pageRepository, indexRepository, onlyThisPageIndexing);
    }

    private void stopSiteIndexing(Site siteToStop) {
        SiteDto siteDto = SiteMapper.INSTANCE.siteToSiteDto(siteToStop);
        siteDto.setFailed(INDEXING_STOPPED_BY_USER_MESSAGE);
        siteRepository.saveAndFlush(SiteMapper.INSTANCE.siteDtoToSite(siteDto));
        log.warn("[" + siteToStop.getUrl() + "] " + INDEXING_STOPPED_BY_USER_MESSAGE);
    }

    @Override
    @Transactional
    public int indexLemmas(IndexRepository indexRepository,
                           List<Lemma> lemmasToIndex,
                           Map<String, Integer> pageLemmasCount,
                           PageDto pageDto) {
        List<Index> indexToSaveList = new ArrayList<>();
        for (Lemma lemma : lemmasToIndex) {
            String lemmaValue = lemma.getLemma();
            if (!pageLemmasCount.containsKey(lemmaValue)) {
                log.warn("Не удалось найти лемму \"" + lemmaValue + "\" в " + pageLemmasCount);
                continue;
            }
            Integer lemmaCount = pageLemmasCount.get(lemmaValue);
            indexToSaveList.add(new Index(null, pageDto.getId(), lemma.getId(), lemmaCount.floatValue()));
        }
        return indexRepository.saveAllAndFlush(indexToSaveList).size();
    }

}
