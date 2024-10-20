package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.requests.*;
import searchengine.dto.responses.*;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.StatisticsService;
import searchengine.services.indexing.exceptions.*;
import searchengine.services.indexing.IndexingService;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final IndexingService indexingService;
    private final StatisticsService statisticsService;

    public ApiController(IndexingService indexingService,
                         StatisticsService statisticsService) {
        this.indexingService = indexingService;
        this.statisticsService = statisticsService;
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<? extends ResponseDto> startIndexing() {
        try {
            indexingService.indexAll();
        } catch (IndexingAlreadyLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, "Индексация уже запущена"));
        }
        return ResponseEntity.ok(new SuccessResponseDto(true));
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<? extends ResponseDto> stopIndexing() {
        try {
            indexingService.stopAll();
        } catch (IndexingIsNotLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, "Индексация не запущена"));
        }
        return ResponseEntity.ok(new SuccessResponseDto(true));
    }

    @PostMapping("/indexPage")
    public ResponseEntity<? extends ResponseDto> indexPage(@RequestBody UrlRequestDto urlRequestDto) {
        String url = urlRequestDto.getUrl();
        try {
            indexingService.indexPage(url);
        } catch (ConfigSiteNotFoundException | IndexingAlreadyLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, e.getLocalizedMessage()));
        }
        return ResponseEntity.ok(new SuccessResponseDto(true));
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }
}
