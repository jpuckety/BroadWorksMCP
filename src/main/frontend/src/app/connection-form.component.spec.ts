import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ConnectionFormComponent } from './connection-form.component';
import { ConnectionsService } from './connections.service';

describe('ConnectionFormComponent', () => {
  let fixture: ComponentFixture<ConnectionFormComponent>;
  let service: {
    get: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    update: ReturnType<typeof vi.fn>;
    verify: ReturnType<typeof vi.fn>;
  };

  async function setup(id: string | null): Promise<void> {
    service = {
      get: vi.fn(() => of({
        resourceId: 'c1',
        displayName: 'Office OCI',
        hostname: 'bw.example.com',
        port: 2208,
        username: 'admin',
        needsPassword: false
      })),
      create: vi.fn(() => of({
        resourceId: 'c2',
        displayName: 'Lab',
        hostname: 'lab.example.com',
        port: 2208,
        username: 'labuser',
        needsPassword: true
      })),
      update: vi.fn(() => of({
        resourceId: 'c1',
        displayName: 'Office OCI',
        hostname: 'bw.example.com',
        port: 2208,
        username: 'admin',
        needsPassword: false
      })),
      verify: vi.fn(() => of({ success: true, message: 'Login succeeded.' }))
    };

    await TestBed.configureTestingModule({
      imports: [ConnectionFormComponent],
      providers: [
        provideRouter([]),
        { provide: ConnectionsService, useValue: service },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => id } } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ConnectionFormComponent);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function fillCreateForm(compiled: HTMLElement): void {
    setInput(compiled.querySelector('#displayName'), 'Lab');
    setInput(compiled.querySelector('#hostname'), 'lab.example.com');
    setInput(compiled.querySelector('#port'), '2208');
    setInput(compiled.querySelector('#username'), 'labuser');
    setInput(compiled.querySelector('#password'), 'secret');
    fixture.detectChanges();
  }

  function setInput(el: HTMLInputElement | null, value: string): void {
    if (!el) {
      return;
    }
    el.value = value;
    el.dispatchEvent(new Event('input'));
  }

  it('renders sectioned cards for a new connection', async () => {
    await setup(null);
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Add connection');
    expect(compiled.textContent).toContain('Connection details');
    expect(compiled.textContent).toContain('Credentials');
    expect(compiled.textContent).toContain('Test connection');
    expect(compiled.querySelectorAll('.card').length).toBe(3);
    expect(compiled.querySelector('#password')?.getAttribute('type')).toBe('password');
    expect(compiled.querySelector<HTMLButtonElement>('.verify-section button')?.disabled).toBe(true);
  });

  it('verifies with form values and shows the API message', async () => {
    await setup(null);
    const compiled = fixture.nativeElement as HTMLElement;
    fillCreateForm(compiled);

    compiled.querySelector<HTMLButtonElement>('.verify-section button')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(service.verify).toHaveBeenCalledWith({
      hostname: 'lab.example.com',
      port: 2208,
      username: 'labuser',
      password: 'secret',
      resourceId: undefined
    });
    expect(compiled.querySelector('.alert-success')?.textContent).toContain('Login succeeded.');
  });

  it('creates a connection through ConnectionsService', async () => {
    await setup(null);
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const compiled = fixture.nativeElement as HTMLElement;
    fillCreateForm(compiled);

    compiled.querySelector<HTMLFormElement>('form')?.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(service.create).toHaveBeenCalledWith({
      displayName: 'Lab',
      hostname: 'lab.example.com',
      port: 2208,
      username: 'labuser',
      password: 'secret'
    });
    expect(navigate).toHaveBeenCalledWith(['/']);
  });

  it('loads an existing connection and verifies with the stored secret', async () => {
    await setup('c1');
    const compiled = fixture.nativeElement as HTMLElement;
    expect(service.get).toHaveBeenCalledWith('c1');
    expect(compiled.querySelector<HTMLInputElement>('#displayName')?.value).toBe('Office OCI');
    expect(compiled.textContent).toContain('Edit connection');
    expect(compiled.textContent).toContain('Leave blank to keep the stored password.');

    compiled.querySelector<HTMLButtonElement>('.verify-section button')?.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(service.verify).toHaveBeenCalledWith({
      hostname: 'bw.example.com',
      port: 2208,
      username: 'admin',
      password: undefined,
      resourceId: 'c1'
    });
  });

  it('shows a load error when the connection cannot be fetched', async () => {
    await TestBed.configureTestingModule({
      imports: [ConnectionFormComponent],
      providers: [
        provideRouter([]),
        {
          provide: ConnectionsService,
          useValue: {
            get: () => throwError(() => ({ status: 404 })),
            create: vi.fn(),
            update: vi.fn(),
            verify: vi.fn()
          }
        },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'missing' } } } }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(ConnectionFormComponent);
    await fixture.whenStable();
    fixture.detectChanges();

    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.textContent).toContain('Failed to load the connection.');
  });
});
