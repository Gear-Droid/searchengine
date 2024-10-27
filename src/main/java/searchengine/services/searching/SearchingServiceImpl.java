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

    private final LemmasService lemmasService;
    private final IndexRepository indexRepository;
    private final PageRepository pageRepository;

    private final int FREQUENCY_PERCENT_FILTER = 20;  // (в %) порог от суммы frequency поисковых лемм

    @Override
    @Transactional(readOnly = true)
    public SearchResultResponseDto getSearchResults(String query, String site, Integer offset, Integer limit) {
        log.info("Выполняется поиск \"" + query + "\"" + (site != null ? " по сайту: ".concat(site) : "") +
                " с отступом: " + offset + " и лимитом: " + limit);

        if (query.isEmpty())
            return getErrorSearchResultResponseDto("Задан пустой поисковый запрос");

        Map<String, Integer> queryLemmasCount = lemmasService.collectLemmas(query);
        Set<String> queryLemmas = queryLemmasCount.keySet();  // сет лемм поиска

        if (queryLemmasCount.isEmpty())
            return getErrorSearchResultResponseDto("Не удалось распознать текст поиска");

        Set<Lemma> foundLemmas = lemmasService
                .findAllByLemmaInOrderByFrequencyAsc(queryLemmas);  // поиск по леммам из поисковой строки

        if (queryLemmasCount.size() > 2) {  // кол-во уникальных слов поиска больше 2? -> избавляемся от популярных
            removeVeryFrequentLemmas(queryLemmasCount, foundLemmas);
        }

        Set<String> uniqueLemmasToFind = foundLemmas.stream()
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());
        log.info("Леммы, используемые для поиска: " + String.join(", ", uniqueLemmasToFind));

        List<Page> resultPages = getResultPages(foundLemmas, offset, limit);
        return getSuccessSearchResultResponseDto(resultPages, uniqueLemmasToFind);
    }

    private Set<Lemma> getVeryFrequentLemmasSet(Set<Lemma> lemmas) {
        int lemmasFrequencySum = lemmas.stream()
                .map(Lemma::getFrequency)
                .mapToInt(Integer::intValue)
                .sum();
        Set <String> vfls = lemmas.stream()
                .filter(l -> l.getFrequency() >= lemmasFrequencySum * (FREQUENCY_PERCENT_FILTER / 100.))
                .map(Lemma::getLemma)
                .collect(Collectors.toSet());
        return lemmas.stream().
                filter(lemma -> !vfls.contains(lemma.getLemma()))
                .collect(Collectors.toSet());
    }

    private void removeVeryFrequentLemmas(Map<String, Integer> queryLemmasCount, Set<Lemma> foundLemmas) {
        Set<Lemma> veryFrequentLemmas = getVeryFrequentLemmasSet(foundLemmas);
        if (veryFrequentLemmas.isEmpty()) return;

        Set<Lemma> lemmasToRemove = veryFrequentLemmas;
        if (veryFrequentLemmas.size() == foundLemmas.size()) {  // если все леммы популярные
            String minFreqLemma = veryFrequentLemmas.stream()
                    .min(Comparator.comparing(Lemma::getFrequency)).get().getLemma();
            lemmasToRemove = veryFrequentLemmas.stream()
                    .filter(l -> !l.getLemma().equals(minFreqLemma))
                    .collect(Collectors.toSet());
        }
        foundLemmas.removeAll(lemmasToRemove);

        veryFrequentLemmas.forEach(vfl -> queryLemmasCount.remove(vfl.getLemma()));
        log.info("Избавились от популярных лемм: " +
                String.join(", ", veryFrequentLemmas.stream()
                        .map(Lemma::getLemma)
                        .distinct()
                        .toList()));
    }

    private Set<Integer> recursivePageIdSetIntersectionByLemmaIds(Set<Lemma> foundLemmas) {
        Optional<Lemma> optionalFirstLemma = foundLemmas.stream().findFirst();
        if (optionalFirstLemma.isEmpty()) return Set.of();

        String currentLemmaValue = optionalFirstLemma.get().getLemma();  // значение текущей леммы
        Set<Lemma> lemmasToFind = foundLemmas.stream()
                .filter(lemma -> lemma.getLemma().equals(currentLemmaValue))
                .collect(Collectors.toSet());  // отбираем все записи выбранной леммы в БД
        foundLemmas.removeAll(lemmasToFind);  // убираем выбранные леммы из foundLemmas

        Set<Integer> lemmaIdListToFind = lemmasToFind.stream()
                .map(Lemma::getId)
                .collect(Collectors.toSet());
        Set<Index> indexes = indexRepository.findByLemmaIdIn(lemmaIdListToFind);  // индексы с текущей леммой

        Set<Integer> foundPageIdSet = indexes.stream()
                .map(Index::getPageId)
                .collect(Collectors.toSet());  // страницы с текущей леммой

        if (foundLemmas.isEmpty()) return foundPageIdSet;

        Set<Integer> nextLemmaIndexFindResult =
                recursivePageIdSetIntersectionByLemmaIds(foundLemmas);  // вызов обработки следующей леммы
        foundPageIdSet.retainAll(nextLemmaIndexFindResult);  // пересекаем прошлые страницы с текущими

        return foundPageIdSet;
    }

    private SearchResponseData getSearchResponseData(Page resultPage, Set<String> lemmasToFind) {
        SearchResponseData pageData = new SearchResponseData();
        Site pageSite = resultPage.getSite();
        pageData.setSite(pageSite.getUrl());
        pageData.setSiteName(pageSite.getName());
        pageData.setUri(resultPage.getPath());
        pageData.setTitle(resultPage.getTitle());
        pageData.setSnippet(resultPage.getSnippetFromContentByLemmaValues(lemmasToFind));
        return pageData;
    }

    List<PageRelevance> getPageRelevanceListByLemmaIdSetAndPageIdSet(Set<Integer> lemmaIdSet,
                                                                     Set<Integer> pageIdSet,
                                                                     int offset, int limit) {
        List<Map<String, Number>> mapList = indexRepository.getPageIdAndRelevanceByLemmaIdSetAndPageIdSet(
                lemmaIdSet, pageIdSet, offset, limit);

        return new ArrayList<>(mapList.stream()
                .map(PageRelevance::new)
                .toList());
    }

    private List<Page> getResultPages(Set<Lemma> foundLemmas, Integer offset, Integer limit) {
        Set<Integer> foundPageIdSet =
                recursivePageIdSetIntersectionByLemmaIds(new HashSet<>(foundLemmas));  // рекурсивное сложение pageId искомых лемм
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

    private SearchResultResponseDto getSuccessSearchResultResponseDto(
            List<Page> resultPages, Set<String> lemmasToFind) {
        List<SearchResponseData> searchResponseDataList = new ArrayList<>();

        for (Page resultPage : resultPages) {
            SearchResponseData pageData = getSearchResponseData(resultPage, lemmasToFind);
            searchResponseDataList.add(pageData);
        }

        SearchResultResponseDto response = new SearchResultResponseDto();
        response.setResult(true);
        int count = resultPages.size();
        response.setCount(count);
        response.setData(searchResponseDataList);
        logFinalResult(count);
        return response;
    }

    private SearchResultResponseDto getErrorSearchResultResponseDto(String errorText) {
        SearchResultResponseDto response = new SearchResultResponseDto();
        response.setResult(false);
        response.setError(errorText);
        logFinalResult(0);
        return response;
    }

    private void logFinalResult(int count) {
        String message = "Поиск завершен, найдено " + count + " результатов";
        log.info(message);
    }

}
