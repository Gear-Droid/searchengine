package searchengine.controllers;

import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.responses.*;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.exceptions.ConfigSiteNotFoundException;
import searchengine.exceptions.IndexingAlreadyLaunchedException;
import searchengine.exceptions.IndexingIsNotLaunchedException;
import searchengine.services.indexing.utils.PageIndexator;
import searchengine.services.statistics.StatisticsService;
import searchengine.services.indexing.IndexingService;
import searchengine.services.searching.SearchingService;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class ApiController {

    private final IndexingService indexingService;
    private final StatisticsService statisticsService;
    private final SearchingService searchingService;

    @ExceptionHandler({ IndexingAlreadyLaunchedException.class,
                        IndexingIsNotLaunchedException.class,
                        ConfigSiteNotFoundException.class })
    public ResponseEntity<ErrorResponseDto> apiErrorHandler(RuntimeException e) {
        ErrorResponseDto response = new ErrorResponseDto(false, e.getLocalizedMessage());
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @GetMapping("/startIndexing")
    public SuccessResponseDto startIndexing() {
        List<PageIndexator> indexingTasks = indexingService.initSitesIndexingTasks();
        indexingService.submitAll(indexingTasks);
        return new SuccessResponseDto(true);
    }

    @GetMapping("/stopIndexing")
    public SuccessResponseDto stopIndexing() {
        indexingService.stopAll();
        return new SuccessResponseDto(true);
    }

    @PostMapping("/indexPage")
    public SuccessResponseDto indexPage(@RequestParam String url) {
        PageIndexator task = indexingService.initPageIndexingTask(url);
        indexingService.submitAll(List.of(task));
        return new SuccessResponseDto(true);
    }

    @GetMapping("/statistics")
    public StatisticsResponse statistics() {
        return statisticsService.getStatistics();
    }

    @GetMapping("/search")
    public SearchResultResponseDto search(@RequestParam String query,
                                                          @RequestParam(required = false) String site,
                                                          @RequestParam(required = false, defaultValue = "0") Integer offset,
                                                          @RequestParam(required = false, defaultValue = "10") Integer limit) {
        return searchingService.getSearchResults(query, site, offset, limit);
    }
}
