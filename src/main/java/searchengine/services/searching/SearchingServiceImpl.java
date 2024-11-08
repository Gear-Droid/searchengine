package searchengine.services.searching;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import searchengine.dto.searching.PageRelevance;
import searchengine.dto.responses.*;
import searchengine.model.*;
import searchengine.repositories.*;
import searchengine.services.morphology.LemmasService;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchingServiceImpl implements SearchingService {

    static final double FREQUENCY_PERCENT_FILTER = 20.;  // (в %) порог от суммы frequency поисковых лемм

    private final LemmasService lemmasService;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;

    @Override
    @Transactional(readOnly = true)
    public SearchResultResponseDto getSearchResults(String query, String site, Integer offset, Integer limit) {
        log.info("Выполняется поиск \"" + query + "\"" + (site != null ? " по сайту: ".concat(site) : "") +
                " с отступом: " + offset + " и лимитом: " + limit);
        if (query.isEmpty()) return getErrorSearchResultResponseDto("Задан пустой поисковый запрос");

        Set<String> queryLemmas = lemmasService.getLemmaSet(query);  // сет лемм из поиска
        if (queryLemmas.isEmpty())
            return getErrorSearchResultResponseDto("Не удалось распознать текст поиска");

        Set<Lemma> foundLemmas = lemmasService
                .findAllByLemmaInOrderByFrequencyAsc(queryLemmas);  // поиск по леммам из поисковой строки

        if (queryLemmas.size() < 3 && foundLemmas.size() > limit) {
            removeVeryFrequentLemmas(foundLemmas);  // уникальных слов поиска меньше 3? -> убираем популярные леммы
        }

        Set<String> uniqueLemmasToFind = foundLemmas.stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());
        log.info("Леммы, используемые для поиска: " + String.join(", ", uniqueLemmasToFind));
        return getSuccessSearchResultResponseDto(getResultPages(foundLemmas, offset, limit), uniqueLemmasToFind);
    }

    /**
     * Функция удаления наиболее популярных лемм в пороговом значении FREQUENCY_PERCENT_FILTER
     * @param foundLemmas найденные
     * **/
    private void removeVeryFrequentLemmas(Set<Lemma> foundLemmas) {
        int countToRemove = (int) (foundLemmas.size() * (FREQUENCY_PERCENT_FILTER / 100.));
        if (countToRemove == 0 || countToRemove == foundLemmas.size()) return;

        Set<Lemma> lemmasToRemove = foundLemmas.stream()
                .sorted(Comparator.comparing(lemma -> -lemma.getFrequency()))
                .limit(countToRemove)
                .collect(Collectors.toSet());
        foundLemmas.removeAll(lemmasToRemove);

        log.info("Избавились от популярных лемм: " +
                String.join(", ", lemmasToRemove.stream()
                        .map(Lemma::getLemma)
                        .distinct()
                        .toList()));
    }

    private Set<Integer> recursivePageIdSetIntersectionByLemmaIds(Set<Lemma> foundLemmas) {
        Optional<Lemma> optionalFirstLemma = foundLemmas.stream().findFirst();
        if (optionalFirstLemma.isEmpty()) return Set.of();

        Set<Lemma> lemmasToFind = foundLemmas.stream()
                .filter(lemma -> lemma.getLemma().equals(optionalFirstLemma.get().getLemma()))
                .collect(Collectors.toSet());  // отбираем все записи выбранной леммы в БД
        foundLemmas.removeAll(lemmasToFind);  // убираем выбранные леммы из foundLemmas

        Set<Index> indexes = indexRepository.findByLemmaIdIn(lemmasToFind.stream()
                .map(Lemma::getId)
                .collect(Collectors.toSet()));  // индексы с текущей леммой

        Set<Integer> foundPageIdSet = indexes.stream()
                .map(Index::getPageId)
                .collect(Collectors.toSet());  // страницы с текущей леммой

        if (foundLemmas.isEmpty()) return foundPageIdSet;
        foundPageIdSet.retainAll(recursivePageIdSetIntersectionByLemmaIds(foundLemmas));  // пересекаем леммы
        return foundPageIdSet;
    }

    List<PageRelevance> getPageRelevanceListByLemmaIdSetAndPageIdSet(Set<Integer> lemmaIdSet,
                                                                     Set<Integer> pageIdSet,
                                                                     int offset, int limit) {
        List<Map<String, Number>> pagesRelevanceData =
                indexRepository.getPageIdAndRelevanceByLemmaIdSetAndPageIdSet(lemmaIdSet, pageIdSet, offset, limit);
        return pagesRelevanceData.stream()
                .map(pageRelevanceSqlResult -> {
                    PageRelevance relevance = new PageRelevance();
                    pageRelevanceSqlResult.forEach((key, value) -> {
                        switch (key) {
                            case "page_id" -> relevance.setPageId((Integer) value);
                            case "absolute_relevance" -> relevance.setAbsoluteRelevance((Double) value);
                            case "relative_relevance" -> relevance.setRelativeRelevance((Double) value);
                            default -> throw new RuntimeException();
                        }
                    });
                    return relevance;
                })
                .toList();
    }

    private List<Page> getResultPages(Set<Lemma> foundLemmas, Integer offset, Integer limit) {
        Set<Integer> foundPageIdSet = recursivePageIdSetIntersectionByLemmaIds(
                new HashSet<>(foundLemmas));  // рекурсивное сложение pageId искомых лемм
        Set<Integer> foundLemmasIdSet = foundLemmas.stream()
                .map(Lemma::getId)
                .collect(Collectors.toSet());
        List<PageRelevance> pageRelevanceList =
                getPageRelevanceListByLemmaIdSetAndPageIdSet(foundLemmasIdSet, foundPageIdSet, offset, limit);

        return pageRelevanceList.isEmpty() ? new ArrayList<>() :
                pageRepository.findAllById(pageRelevanceList.stream()
                        .map(PageRelevance::getPageId)
                        .collect(Collectors.toSet()));
    }

    private SearchResponseData getSearchResponseData(Page resultPage, Set<String> lemmasToFind) {
        SearchResponseData pageSearchResponseData = new SearchResponseData(resultPage);
        pageSearchResponseData.setSnippet(
                lemmasService.getSnippetFromContentByLemmaValues(resultPage.getContent(), lemmasToFind));
        return pageSearchResponseData;
    }

    private SearchResultResponseDto getSuccessSearchResultResponseDto(
            List<Page> resultPages, Set<String> lemmasToFind) {
        List<SearchResponseData> searchResponseDataList = new ArrayList<>();
        resultPages.forEach((resultPage) ->
                searchResponseDataList.add(getSearchResponseData(resultPage, lemmasToFind)));
        logFinalResult(resultPages.size());
        return new SearchResultResponseDto(true, resultPages.size(), searchResponseDataList, "");
    }

    private SearchResultResponseDto getErrorSearchResultResponseDto(String errorText) {
        logFinalResult(0);
        return new SearchResultResponseDto(false, 0, List.of(), errorText);
    }

    private void logFinalResult(int count) {
        log.info("Поиск завершен, найдено " + count + " результатов");
    }

}
