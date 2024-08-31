package searchengine.dto;

import lombok.*;
import searchengine.model.SiteStatus;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
public class SiteDto {

    private Integer id;

    private SiteStatus status;

    private LocalDateTime statusTime;  // дата и время статуса

    private String lastError;  // текст ошибки индексации

    private String url;  // адрес главной страницы сайта

    private String name;  // имя сайта

}