import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let httpClient: HttpClient;
  let http: HttpTestingController;
  let auth: AuthService;
  const navigate = vi.fn();

  beforeEach(() => {
    navigate.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: Router, useValue: { url: '/approvals/a1', navigate } }
      ]
    });
    httpClient = TestBed.inject(HttpClient);
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => http.verify());

  it('does not attach an Authorization header', () => {
    httpClient.get('/api/portal/connections').subscribe({ error: () => undefined });
    const req = http.expectOne('/api/portal/connections');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush([]);
  });

  it('clears the user and navigates to login on a 401', () => {
    const clear = vi.spyOn(auth, 'clearUser');
    httpClient.get('/api/portal/connections').subscribe({ error: () => undefined });
    http.expectOne('/api/portal/connections').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(clear).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { returnUrl: '/approvals/a1' } });
  });

  it('ignores 401 from /api/portal/me to avoid a login loop', () => {
    httpClient.get('/api/portal/me').subscribe({ error: () => undefined });
    http.expectOne('/api/portal/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(navigate).not.toHaveBeenCalled();
  });

  it('ignores 401 from /api/portal/logout', () => {
    httpClient.post('/api/portal/logout', {}).subscribe({ error: () => undefined });
    http.expectOne('/api/portal/logout').flush(null, { status: 401, statusText: 'Unauthorized' });
    expect(navigate).not.toHaveBeenCalled();
  });
});
