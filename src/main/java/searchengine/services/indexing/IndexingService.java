package searchengine.services.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.indexing.*;
import searchengine.model.*;
import searchengine.repositories.IndexRepository;
import searchengine.services.indexing.exceptions.*;

import java.util.*;

public interface IndexingService {

    Logger log = LoggerFactory.getLogger(IndexingService.class);

    Hashtable<String, Repository> repositories = new Hashtable<>();

    /**
     * Метод обхода сайтов из конфигурационного файла, и вызова их обработки
     * @throws IndexingAlreadyLaunchedException если уже запущена индексация
     * **/
    void indexAll();

    /**
     * Метод для остановки индексации сайтов
     * @throws IndexingIsNotLaunchedException если нет индексируемых сайтов
     */
    void stopAll();

    /**
     * @param queryUrl адрес страницы для индексации
     * @throws ConfigSiteNotFoundException если страница находится за пределами сайтов из конфига
     * @throws IndexingAlreadyLaunchedException если уже запущена
     */
    void indexPage(String queryUrl);

    /**
     * @param lemmasToIndex список объектов Lemma для индексации
     * @param pageLemmasCount key-value мапа текущей страницы: "лемма" - "кол-во на странице"
     * @param pageDto Dto с инфой страницы
     */
    @Transactional
    static int indexLemmas(IndexRepository indexRepository,
                           List<Lemma> lemmasToIndex,
                           Map<String, Integer> pageLemmasCount,
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

        return indexRepository.saveAllAndFlush(indexToSaveList).size();
    }

    @Transactional(readOnly = true)
    static SiteDto siteToSiteDto(Site site) {
        SiteDto siteDto = new SiteDto();
        siteDto.setId(site.getId());
        siteDto.setStatus(site.getStatus());
        siteDto.setStatusTime(site.getStatusTime());
        siteDto.setLastError(site.getLastError());
        siteDto.setUrl(site.getUrl());
        siteDto.setName(site.getName());
        return siteDto;
    }

    @Transactional(readOnly = true)
    static Site siteDtoToSite(SiteDto siteDto) {
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
