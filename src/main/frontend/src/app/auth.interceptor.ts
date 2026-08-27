import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

function isIgnoredAuthUrl(url: string): boolean {
  return url.includes('/api/portal/me') || url.includes('/api/portal/logout');
}

/**
 * On 401, clear the user and send them to login. Ignores `/api/portal/me` and `/api/portal/logout`
 * so the guard/login `me()` probe and sign-out cannot loop. Cookie session only — no Bearer header.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && !isIgnoredAuthUrl(req.url)) {
        auth.clearUser();
        if (!router.url.startsWith('/login')) {
          void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
        }
      }
      return throwError(() => err);
    })
  );
};
