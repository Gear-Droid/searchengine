package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import searchengine.model.Lemma;

import java.util.Set;

public interface LemmaRepository extends JpaRepository<Lemma, Integer> {

    Set<Lemma> findAllBySiteId(int siteId);

    @Query(value = "SELECT COUNT(1) FROM lemma WHERE site_id = :siteId", nativeQuery = true)
    int countAllBySiteId(int siteId);

    Set<Lemma> findAllByLemmaInOrderByFrequencyAsc(Set<String> lemmas);

}
