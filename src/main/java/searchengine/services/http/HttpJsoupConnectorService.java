package searchengine.services.http;

import searchengine.dto.indexing.PageDto;

public interface HttpJsoupConnectorService {

    int REQUEST_TIMEOUT = 1_000;  // (мс) таймаут перед запросами к ссылкам

    PageDto getPageDtoFromLink(String link) throws InterruptedException;

}
