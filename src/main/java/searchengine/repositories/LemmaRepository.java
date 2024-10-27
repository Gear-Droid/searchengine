package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Lemma;

import java.util.Set;

public interface LemmaRepository extends JpaRepository<Lemma, Integer> {

    /**
     * @param siteId ID сайта
     * @return список подходящих слов
     *
     * <p>Для создания SQL запроса, необходимо указать nativeQuery = true</p>
     * <p>каждый параметр в SQL запросе можно вставить, используя запись :ИМЯ_ПЕРЕМEННОЙ
     * перед именем двоеточие, так hibernate поймет, что надо заменить на значение переменной</p>
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    @Query(value = "SELECT * FROM lemma WHERE site_id = :siteId", nativeQuery = true)
    Set<Lemma> findLemmasBySiteId(int siteId);

    @Query(value = "SELECT COUNT(1) FROM lemma WHERE site_id = :siteId", nativeQuery = true)
    int countAllBySiteId(int siteId);

    Set<Lemma> findAllByLemmaInOrderByFrequencyAsc(Set<String> lemmas);

}
