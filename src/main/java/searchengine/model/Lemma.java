package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@Entity
@Table(name = "lemma")
public class Lemma {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "site_id", nullable = false)
    private Integer siteId;  // ID веб-сайта из таблицы site

    @Column(name = "lemma", nullable = false)
    private String lemma;  // нормальная форма слова (лемма)

    @Column(name = "frequency", nullable = false)
    private Integer frequency;  // количество страниц,
        // на которых слово встречается хотя бы один раз

}
