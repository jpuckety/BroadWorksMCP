import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'DECLINED';

export interface Approval {
  id: string;
  action: string;
  status: ApprovalStatus;
}

export type ApprovalDecision = 'APPROVED' | 'DECLINED';

/**
 * Client for the portal approval API. Same-origin session cookie + Angular XSRF interceptor
 * (XSRF-TOKEN cookie echoed as X-XSRF-TOKEN), matching {@link ConnectionsService}.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/portal/approvals';

  get(id: string): Observable<Approval> {
    return this.http.get<Approval>(`${this.base}/${encodeURIComponent(id)}`);
  }

  decide(id: string, decision: ApprovalDecision): Observable<Approval> {
    return this.http.post<Approval>(`${this.base}/${encodeURIComponent(id)}`, { decision });
  }
}
