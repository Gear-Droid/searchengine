package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import searchengine.model.Page;

public interface PageRepository extends JpaRepository<Page, Integer> {

    @Query(value = "SELECT COUNT(1) FROM page WHERE site_id = :siteId", nativeQuery = true)
    int countAllBySiteId(int siteId);
}
