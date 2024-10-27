package searchengine.dto.indexing;

import lombok.*;
import searchengine.model.SiteStatus;

import java.time.LocalDateTime;

@Getter @Setter
@NoArgsConstructor
public class SiteDto {

    private Integer id;

    private SiteStatus status;

    private LocalDateTime statusTime;  // дата и время статуса

    private String lastError;  // текст ошибки индексации

    private String url;  // адрес главной страницы сайта

    private String name;  // имя сайта

    public void updateStatusTime() {
        statusTime = null;  // если оставить null, то @UpdateTimestamp в Site проставит текущее время
    }

    public void setIndexing() {
        status = SiteStatus.INDEXING;
        updateStatusTime();
    }

    public void setIndexed() {
        status = SiteStatus.INDEXED;
        updateStatusTime();
    }

    public void setFailed(String errorText) {
        status = SiteStatus.FAILED;
        if (errorText != null) lastError = errorText;
        updateStatusTime();
    }

}