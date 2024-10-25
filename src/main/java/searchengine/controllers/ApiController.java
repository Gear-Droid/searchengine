package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.responses.*;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.indexing.utils.PageIndexator;
import searchengine.services.statistics.StatisticsService;
import searchengine.services.indexing.exceptions.*;
import searchengine.services.indexing.IndexingService;
import searchengine.services.searching.SearchingService;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final IndexingService indexingService;
    private final StatisticsService statisticsService;
    private final SearchingService searchingService;

    public ApiController(IndexingService indexingService,
                         StatisticsService statisticsService,
                         SearchingService searchingService) {
        this.indexingService = indexingService;
        this.statisticsService = statisticsService;
        this.searchingService = searchingService;
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<? extends ResponseDto> startIndexing() {
        try {
            Map<String, PageIndexator> indexationTasks = indexingService.prepareIndexingTasks();
            indexingService.submitAll(indexationTasks, true);
        } catch (IndexingAlreadyLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, e.getLocalizedMessage()));
        }
        return ResponseEntity.ok(new SuccessResponseDto(true));
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<? extends ResponseDto> stopIndexing() {
        try {
            indexingService.stopAll();
        } catch (IndexingIsNotLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, e.getLocalizedMessage()));
        }
        return ResponseEntity.ok(new SuccessResponseDto(true));
    }

    @PostMapping("/indexPage")
    public ResponseEntity<? extends ResponseDto> indexPage(@RequestParam String url) {
        try {
            PageIndexator task = indexingService.preparePageIndexingTask(url);
            indexingService.submitAll(Map.of(url, task), false);
        } catch (ConfigSiteNotFoundException | IndexingAlreadyLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, e.getLocalizedMessage()));
        }
        return ResponseEntity.ok(new SuccessResponseDto(true));
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResultResponseDto> search(@RequestParam String query,
                                                          @RequestParam(required = false) String site,
                                                          @RequestParam(required = false) Integer offset,
                                                          @RequestParam(required = false) Integer limit) {
        if (offset == null) offset = 0;
        if (limit == null) limit = 20;
        return ResponseEntity.ok(searchingService.getSearchResults(query, site, offset, limit));
    }
}
