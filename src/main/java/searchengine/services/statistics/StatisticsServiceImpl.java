package searchengine.services.statistics;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Example;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.config.ConfigSite;
import searchengine.config.ConfigSiteList;
import searchengine.dto.statistics.*;
import searchengine.model.SiteStatus;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.services.morphology.LemmasService;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    private final LemmasService lemmasService;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;

    private final ConfigSiteList sites;

    @Override
    @Transactional(readOnly = true)
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

        StatisticsData data = new StatisticsData(total, detailed);
        return new StatisticsResponse(true, data);
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
        item.setStatus(SiteStatus.FAILED.name());
        item.setError("");
        item.setStatusTime(System.currentTimeMillis());
        return item;
    }

    private DetailedStatisticsItem fillItem(DetailedStatisticsItem item, searchengine.model.Site site) {
        item.setPages(pageRepository.countAllBySiteId(site.getId()));
        item.setLemmas(lemmasService.countAllBySiteId(site.getId()));
        item.setStatus(site.getStatus().name());
        if (item.getStatus().equals(SiteStatus.FAILED.name())) item.setError(site.getLastError());
        item.setStatusTime(site.getStatusTime().toEpochSecond(ZoneOffset.UTC));
        return item;
    }

}
