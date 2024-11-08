package searchengine.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import searchengine.dto.indexing.SiteDto;
import searchengine.model.Site;

@Mapper
public interface SiteMapper {

    SiteMapper INSTANCE = Mappers.getMapper( SiteMapper.class );

    SiteDto siteToSiteDto(Site site);
    Site siteDtoToSite(SiteDto site);

}