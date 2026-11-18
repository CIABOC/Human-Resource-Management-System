package lk.gov.ciaboc.hrms.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MasterDataItemRepository extends JpaRepository<MasterDataItem, Long> {
    List<MasterDataItem> findByCategoryOrderBySortOrderAscNameEnAsc(String category);

    List<MasterDataItem> findByCategoryAndActiveTrueOrderBySortOrderAscNameEnAsc(String category);

    Optional<MasterDataItem> findByCategoryAndCode(String category, String code);

    boolean existsByCategoryAndCodeAndActiveTrue(String category, String code);
}
