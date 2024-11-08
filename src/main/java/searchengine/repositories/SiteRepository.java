package searchengine.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import searchengine.model.Site;
import searchengine.model.SiteStatus;

import java.util.List;
import java.util.Optional;

public interface SiteRepository extends JpaRepository<Site, Integer> {

    List<Site> findAllByStatus(SiteStatus status);

    List<Site> findAllByUrl(String url);

    Optional<Site> findOneByUrl(String url);

}
