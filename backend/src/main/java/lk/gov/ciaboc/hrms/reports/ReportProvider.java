package lk.gov.ciaboc.hrms.reports;

import java.util.List;

/** Implemented by each module (as a Spring bean) to contribute its reports to the catalogue. */
public interface ReportProvider {
    List<ReportDefinition> reports();
}
