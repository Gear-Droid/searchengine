package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.statistics.DetailedStatisticsItem;
import searchengine.dto.statistics.StatisticsData;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.dto.statistics.TotalStatistics;
import searchengine.model.SiteStatus;
import searchengine.repositories.LemmaRepository;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private final LemmaRepository lemmaRepository;

    private final static String[] statuses = { "INDEXED", "FAILED", "INDEXING" };
    private final static String[] errors = {
            "Ошибка индексации: главная страница сайта не доступна",
            "Ошибка индексации: сайт не доступен",
            ""
    };

    private final SitesList sites;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(isAnySiteIndexing());

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        List<Site> sitesList = sites.getSites();
        for (Site site : sitesList) {
            String url = site.getUrl();
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            item.setName(site.getName());
            item.setUrl(url);

            Optional<searchengine.model.Site> optionalSiteEntity = findSiteByUrl(url);
            if (optionalSiteEntity.isEmpty()) {
                item = emptyItem(item);
            } else {
                searchengine.model.Site siteEntity = optionalSiteEntity.get();
                item = fillItem(item, siteEntity);
            }

            total.setPages(total.getPages() + item.getPages());
            total.setLemmas(total.getLemmas() + item.getLemmas());
            detailed.add(item);
        }

        StatisticsResponse response = new StatisticsResponse();
        StatisticsData data = new StatisticsData();
        data.setTotal(total);
        data.setDetailed(detailed);
        response.setStatistics(data);
        response.setResult(true);
        return response;
    }

    private boolean isAnySiteIndexing() {
        searchengine.model.Site exampleSiteEntity = new searchengine.model.Site();
        exampleSiteEntity.setStatus(SiteStatus.INDEXING);
        Example<searchengine.model.Site> siteExample = Example.of(exampleSiteEntity);
        int count = siteRepository.findAll(siteExample).size();
        return count > 0;
    }

    private Optional<searchengine.model.Site> findSiteByUrl(String url) {
        searchengine.model.Site exampleSiteEntity = new searchengine.model.Site();
        exampleSiteEntity.setUrl(url);
        Example<searchengine.model.Site> siteExample = Example.of(exampleSiteEntity);
        return siteRepository.findOne(siteExample);
    }

    private DetailedStatisticsItem emptyItem(DetailedStatisticsItem item) {
        item.setPages(0);
        item.setLemmas(0);
        item.setStatus(statuses[1]);
        item.setError(errors[2]);
        item.setStatusTime(System.currentTimeMillis());
        return item;
    }

    private DetailedStatisticsItem fillItem(DetailedStatisticsItem item, searchengine.model.Site site) {
        int siteId = site.getId();
        int pagesCount = pageRepository.countAllBySiteId(siteId);
        item.setPages(pagesCount);
        int lemmasCount = lemmaRepository.countAllBySiteId(siteId);
        item.setLemmas(lemmasCount);
        item.setStatus(site.getStatus().name());
        if (item.getStatus().equals(statuses[1])) item.setError(site.getLastError());
        item.setStatusTime(site.getStatusTime().toEpochSecond(ZoneOffset.UTC));
        return item;
    }

}
