package searchengine.services.indexing;

import searchengine.dto.indexing.*;
import searchengine.exceptions.ConfigSiteNotFoundException;
import searchengine.exceptions.IndexingAlreadyLaunchedException;
import searchengine.exceptions.IndexingIsNotLaunchedException;
import searchengine.model.*;
import searchengine.repositories.IndexRepository;
import searchengine.services.indexing.utils.PageIndexator;

import java.util.*;

public interface IndexingService {

    String INDEXING_STOPPED_BY_USER_MESSAGE = "Индексация остановлена пользователем";

    /**
     * Метод обхода сайтов из конфигурационного файла и подготовки тасок на индексацию
     * @throws IndexingAlreadyLaunchedException если уже запущена индексация
     * **/
    List<PageIndexator> initSitesIndexingTasks();

    /**
     * Метод для подготовки таски на индексацию конкретной страницы
     * @param queryUrl адрес страницы для индексации
     * @throws ConfigSiteNotFoundException если страница находится за пределами сайтов из конфига
     * @throws IndexingAlreadyLaunchedException если уже запущена
     */
    PageIndexator initPageIndexingTask(String queryUrl);

    /**
     * Метод запуска подготовленных тасок на индексацию
     * @param tasksToSubmit список таск для подтверждения и запуска
     * **/
    void submitAll(List<PageIndexator> tasksToSubmit);

    /**
     * Метод для остановки индексации сайтов
     * @throws IndexingIsNotLaunchedException если нет индексируемых сайтов
     */
    void stopAll();

    /**
     * @param indexRepository репозиторий индексов
     * @param lemmasToIndex список объектов Lemma для индексации
     * @param pageLemmasCount key-value мапа текущей страницы: "лемма" - "кол-во на странице"
     * @param pageDto Dto с инфой страницы
     * @return кол-во сохраненных индексов
     */
    int indexLemmas(IndexRepository indexRepository,
                           List<Lemma> lemmasToIndex,
                           Map<String, Integer> pageLemmasCount,
                           PageDto pageDto);
}
