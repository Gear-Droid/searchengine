package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import searchengine.model.Index;

import java.util.List;
import java.util.Map;
import java.util.Set;


public interface IndexRepository extends JpaRepository<Index, Integer> {

    Set<Index> findAllByPageId(Integer pageId);

    Set<Index> findByLemmaIdIn(Set<Integer> lemmaIdList);

    @Query(value = "WITH pages_ranks_sum AS (" +
                "SELECT page_id, SUM(`rank`) AS rank_sum " +
                "FROM `index` AS i " +
                "JOIN lemma AS l on i.lemma_id = l.id " +
                "WHERE lemma_id IN (:lemmaIdSet) and page_id IN (:pageIdSet) " +
                "GROUP BY i.page_id" +
            ")" +
            "SELECT page_id, rank_sum as absolute_relevance, " +
                    "rank_sum / (select max(rank_sum) from pages_ranks_sum) AS relative_relevance " +
            "FROM pages_ranks_sum " +
            "ORDER BY relative_relevance DESC " +
            "LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<Map<String, Number>> getPageIdAndRelevanceByLemmaIdSetAndPageIdSet(Set<Integer> lemmaIdSet,
                                                                            Set<Integer> pageIdSet,
                                                                            int offset,
                                                                            int limit);

}
