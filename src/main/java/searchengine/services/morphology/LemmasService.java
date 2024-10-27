package searchengine.services.morphology;

import searchengine.dto.indexing.SiteDto;
import searchengine.model.Lemma;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface LemmasService {

    Set<String> getLemmaSet(String text);

    Map<String, Integer> collectLemmas(String text);

    List<Lemma> handleLemmas(Map<String, Integer> pageLemmasCount, SiteDto siteDto);

    void decrementLemmasFrequencyOrRemoveByIds(Set<Integer> previousLemmasIds);

    Set<Lemma> findAllByLemmaInOrderByFrequencyAsc(Set<String> queryLemmas);

    int countAllBySiteId(int siteId);

}
