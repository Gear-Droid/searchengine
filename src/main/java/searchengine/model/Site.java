package searchengine.model;

import lombok.Getter;
import lombok.Setter;

import jakarta.persistence.*;
import org.hibernate.annotations.CurrentTimestamp;

import java.time.LocalDateTime;

@Getter @Setter
@Entity
@Table(name = "site")
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "status", nullable = false)
    @Enumerated
    private SiteStatus status;

    @CurrentTimestamp
    @Column(name = "status_time", nullable = false,
            columnDefinition = "DATETIME")
    private LocalDateTime statusTime;  // дата и время статуса
        // (в случае статуса INDEXING дата и время должны обновляться регулярно
        // при добавлении каждой новой страницы в индекс)

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;  // текст ошибки индексации

    @Column(name = "url", nullable = false)
    private String url;  // адрес главной страницы сайта

    @Column(name = "name", nullable = false)
    private String name;  // имя сайта
}
