export interface LegalDocument {
  id: number; docType: 'ACT' | 'GAZETTE' | 'REGULATION' | 'CIRCULAR'; docNumber: string; title: string; issuingAuthority?: string;
  publicationDate?: string; effectiveDate?: string; status: string; verificationStatus: string; verifiedBy?: string; verifiedAt?: string;
  seriesId?: number; versionNo: number; previousVersionId?: number; fileId?: number; fileName?: string; description?: string;
}

export interface LegalSection {
  id: number; documentId: number; parentKey?: string | null; sectionKey: string; level: 'PART' | 'CHAPTER' | 'SECTION' | 'SUBSECTION' | 'CLAUSE';
  number: string; heading?: string; content?: string; sortOrder: number; effectiveFrom: string; effectiveTo?: string | null;
  verificationStatus: string; versionNo: number; previousVersionId?: number; tags: string[]; designationIds: number[];
}

export interface Amendment {
  id: number; originalDocumentId: number; sectionId?: number; amendingDocumentId?: number; amendmentType: string;
  description: string; effectiveDate: string; resultingSectionId?: number;
}

export const DOC_TYPES = ['ACT', 'GAZETTE', 'REGULATION', 'CIRCULAR'];

/** Builds a multipart body with a JSON "data" part and an optional "file" part. */
export function multipart(data: unknown, file?: File | null) {
  const fd = new FormData();
  fd.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }));
  if (file) fd.append('file', file);
  return fd;
}
