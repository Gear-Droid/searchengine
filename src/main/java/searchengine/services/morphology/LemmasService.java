package searchengine.services.morphology;

import searchengine.dto.indexing.SiteDto;
import searchengine.model.Lemma;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface LemmasService {

    /**
     * Метод разделяет текст на слова, находит все леммы и считает их количество.
     * @param text текст из которого будут выбираться леммы
     * @return ключ является леммой, а значение количеством найденных лемм
     */
    Map<String, Integer> collectLemmas(String text);

    /**
     * Метод разделяет текст на слова, и находит сет лемм
     * @param text текст из которого собираем все леммы
     * @return набор уникальных лемм найденных в тексте
     */
    Set<String> getLemmaSet(String text);

    /**
     * Метод обрабатывает найденные на сайте леммы
     * @param foundPageLemmas найденные леммы со страницы сайта
     * @param siteDto Dto с инфой сайта
     */
    List<Lemma> handleLemmas(SiteDto siteDto, Set<String> foundPageLemmas);

    void decrementLemmasFrequencyOrRemoveByIds(Set<Integer> previousLemmasIds);

    Set<Lemma> findAllByLemmaInOrderByFrequencyAsc(Set<String> queryLemmas);

    int countAllBySiteId(int siteId);

    String getSnippetFromContentByLemmaValues(String content, Set<String> lemmaValueSet);

}
