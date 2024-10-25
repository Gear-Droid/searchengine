package searchengine.services.morphology;

import searchengine.dto.indexing.SiteDto;
import searchengine.model.Lemma;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface LemmasService {

    Map<String, Integer> collectLemmas(String text);

    Set<String> getLemmaSet(String text);

    List<Lemma> handleLemmas(Map<String, Integer> pageLemmasCount, SiteDto siteDto);

    void decrementFrequencyOrRemoveByIds(Set<Integer> previousLemmasIds);

}
