import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot, UrlTree, provideRouter } from '@angular/router';
import { Observable } from 'rxjs';
import { authGuard } from './auth.guard';
import { AuthService, PORTAL_RETURN_URL_KEY, PortalUser } from './auth.service';

const me: PortalUser = { email: 'user@example.com', name: 'User', picture: null };

function observeGuard(url: string): { value: boolean | UrlTree | undefined } {
  const holder: { value: boolean | UrlTree | undefined } = { value: undefined };
  const outcome = TestBed.runInInjectionContext(() =>
    authGuard({} as ActivatedRouteSnapshot, { url } as RouterStateSnapshot)
  );
  if (typeof outcome === 'boolean' || outcome instanceof UrlTree) {
    holder.value = outcome;
    return holder;
  }
  (outcome as Observable<boolean | UrlTree>).subscribe((value) => (holder.value = value));
  return holder;
}

describe('authGuard', () => {
  let http: HttpTestingController;
  let router: Router;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting()
      ]
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    http.verify();
    sessionStorage.clear();
  });

  it('sends unauthenticated users to /login with returnUrl', () => {
    const holder = observeGuard('/approvals/a1');
    http.expectOne('/api/portal/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(holder.value instanceof UrlTree).toBe(true);
    const tree = holder.value as UrlTree;
    expect(tree.toString()).toContain('/login');
    expect(tree.queryParams['returnUrl']).toBe('/approvals/a1');
  });

  it('allows navigation after a successful me()', () => {
    const holder = observeGuard('/');
    http.expectOne('/api/portal/me').flush(me);
    expect(holder.value).toBe(true);
    expect(TestBed.inject(AuthService).isAuthenticated()).toBe(true);
  });

  it('restores portalReturnUrl after a successful me()', () => {
    sessionStorage.setItem(PORTAL_RETURN_URL_KEY, '/approvals/a1');
    const holder = observeGuard('/');
    http.expectOne('/api/portal/me').flush(me);

    expect(holder.value instanceof UrlTree).toBe(true);
    expect(router.serializeUrl(holder.value as UrlTree)).toBe('/approvals/a1');
    expect(sessionStorage.getItem(PORTAL_RETURN_URL_KEY)).toBeNull();
  });
});
