package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@Entity
@Table(name = "\"index\"")
public class Index {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "page_id", nullable = false)
    private Integer pageId;  // идентификатор страницы

    @Column(name = "lemma_id", nullable = false)
    private Integer lemmaId;  // идентификатор леммы

    @Column(name = "\"rank\"", nullable = false)
    private Float rank;  // количество данной леммы
        // для данной страницы

}
