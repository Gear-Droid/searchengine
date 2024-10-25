package searchengine.services.statistics;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import searchengine.config.ConfigSite;
import searchengine.config.ConfigSiteList;
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

    private final ConfigSiteList sites;

    @Override
    public StatisticsResponse getStatistics() {
        TotalStatistics total = new TotalStatistics();
        total.setSites(sites.getSites().size());
        total.setIndexing(isAnySiteIndexing());

        List<DetailedStatisticsItem> detailed = new ArrayList<>();
        List<ConfigSite> sitesList = sites.getSites();
        for (ConfigSite site : sitesList) {
            String url = site.getUrl();

            Optional<searchengine.model.Site> optionalSiteEntity = findSiteByUrl(url);
            DetailedStatisticsItem item = new DetailedStatisticsItem();
            optionalSiteEntity.map(value -> fillItem(item, value)).orElseGet(() -> emptyItem(item));
            item.setName(site.getName());
            item.setUrl(url);

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
