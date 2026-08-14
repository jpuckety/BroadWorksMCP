import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConnectionRequest, ConnectionResponse } from './connection';

/**
 * Client for the portal's connection API. All calls are same-origin and rely on the browser session
 * cookie for authentication; mutating requests automatically carry the CSRF token (Angular's XSRF
 * interceptor reads the `XSRF-TOKEN` cookie set by the server). Passwords are only ever sent, never
 * received.
 */
@Injectable({ providedIn: 'root' })
export class ConnectionsService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/portal/connections';

  list(): Observable<ConnectionResponse[]> {
    return this.http.get<ConnectionResponse[]>(this.base);
  }

  get(id: string): Observable<ConnectionResponse> {
    return this.http.get<ConnectionResponse>(`${this.base}/${encodeURIComponent(id)}`);
  }

  create(request: ConnectionRequest): Observable<ConnectionResponse> {
    return this.http.post<ConnectionResponse>(this.base, request);
  }

  update(id: string, request: ConnectionRequest): Observable<ConnectionResponse> {
    return this.http.put<ConnectionResponse>(`${this.base}/${encodeURIComponent(id)}`, request);
  }

  setPassword(id: string, password: string): Observable<ConnectionResponse> {
    return this.http.put<ConnectionResponse>(
      `${this.base}/${encodeURIComponent(id)}/password`, { password });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${encodeURIComponent(id)}`);
  }
}
