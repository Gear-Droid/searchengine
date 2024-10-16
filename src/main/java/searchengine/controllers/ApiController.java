package searchengine.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.dto.requests.UrlRequestDto;
import searchengine.dto.responses.ErrorResponseDto;
import searchengine.dto.responses.ResponseDto;
import searchengine.dto.responses.SuccessResponseDto;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.StatisticsService;
import searchengine.services.indexing.exceptions.IndexingAlreadyLaunchedException;
import searchengine.services.indexing.IndexingService;
import searchengine.services.indexing.exceptions.IndexingIsNotLaunchedException;

import java.util.Random;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final IndexingService indexingService;
    private final StatisticsService statisticsService;

    public ApiController(IndexingService indexingService, StatisticsService statisticsService) {
        this.indexingService = indexingService;
        this.statisticsService = statisticsService;
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<? extends ResponseDto> startIndexing() {
        try {
            indexingService.indexAll();
            return ResponseEntity.ok(new SuccessResponseDto(true));
        } catch (IndexingAlreadyLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, "Индексация уже запущена"));
        }
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<? extends ResponseDto> stopIndexing() {
        try {
            indexingService.stopAll();
            return ResponseEntity.ok(new SuccessResponseDto(true));
        } catch (IndexingIsNotLaunchedException e) {
            return ResponseEntity.ok(new ErrorResponseDto(false, "Индексация не запущена"));
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<? extends ResponseDto> indexPage(@RequestBody UrlRequestDto urlRequestDto) {
        // TODO: Метод добавляет в индекс или обновляет отдельную страницу,
        //  адреc которой передан в параметре.
        //  Если адрес страницы передан неверно, метод должен вернуть
        //  соответствующую ошибку.

        String url = urlRequestDto.getUrl();
        indexingService.indexPage(url);

        boolean isSuccess = new Random().nextBoolean();
        if (isSuccess) {
            return ResponseEntity.ok(new SuccessResponseDto(true));
        } else {
            return ResponseEntity.ok(new ErrorResponseDto(
                false,
                url + " Данная страница находится за пределами сайтов," +
                        "указанных в конфигурационном файле"));
        }
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }
}
