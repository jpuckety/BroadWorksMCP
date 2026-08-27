import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, of, tap } from 'rxjs';

/** Display-only identity from `GET /api/portal/me` (Google ID token claims). */
export interface PortalUser {
  email: string;
  name: string | null;
  picture: string | null;
}

/** sessionStorage key for post-Google deep-link restoration (especially `/approvals/:id`). */
export const PORTAL_RETURN_URL_KEY = 'portalReturnUrl';

export const GOOGLE_AUTHORIZATION_URL = '/oauth2/authorization/google';

/**
 * Cookie-session (BFF) auth. The browser session cookie authenticates `/api/portal/**`; this
 * service never stores a JWT and never sets an `Authorization` header.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly userSignal = signal<PortalUser | null>(null);

  readonly user = this.userSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.userSignal() !== null);

  me(): Observable<PortalUser | null> {
    return this.http.get<PortalUser>('/api/portal/me').pipe(
      tap((user) => this.userSignal.set(user)),
      catchError(() => {
        this.clearUser();
        return of(null);
      })
    );
  }

  logout(): Observable<void> {
    return this.http.post<void>('/api/portal/logout', {}).pipe(
      catchError(() => of(undefined as void)),
      tap(() => this.clearUser())
    );
  }

  clearUser(): void {
    this.userSignal.set(null);
  }

  stashReturnUrl(returnUrl?: string | null): void {
    if (!returnUrl || returnUrl === '/' || returnUrl.startsWith('/login')) {
      return;
    }
    sessionStorage.setItem(PORTAL_RETURN_URL_KEY, returnUrl);
  }

  consumeReturnUrl(): string | null {
    const value = sessionStorage.getItem(PORTAL_RETURN_URL_KEY);
    if (value) {
      sessionStorage.removeItem(PORTAL_RETURN_URL_KEY);
    }
    return value;
  }

  loginWithGoogle(returnUrl?: string | null): void {
    this.stashReturnUrl(returnUrl);
    window.location.href = GOOGLE_AUTHORIZATION_URL;
  }
}
