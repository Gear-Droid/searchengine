package searchengine.services.http;

import searchengine.dto.indexing.PageDto;

public interface HttpJsoupConnectorService {

    PageDto getPageDtoFromLink(String link);

}
