package searchengine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@Entity
@Table(name = "page")
public class Page {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;  // веб-сайт из таблицы site;

    @Column(name = "path", nullable = false, columnDefinition = "TEXT")
    private String path;  // адрес страницы от корня сайта
        // (должен начинаться со слэша, например: /news/372189/);

    @Column(name = "code", nullable = false)
    private Integer code;  // код HTTP-ответа, полученный при запросе
        // страницы (например, 200, 404, 500 или другие);

    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;  // контент страницы (HTML-код)
}
