import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import { Separator } from '@/components/ui/separator';
import { Button } from '@/components/ui/button';
import { BookOpen, Boxes } from 'lucide-react';
import GithubIcon from '@/assets/github.svg';

export interface Crumb {
  label: string;
  href?: string;
}

interface LayoutProps {
  children: ReactNode;
  breadcrumbs: Crumb[];
}

export function Layout({ children, breadcrumbs }: LayoutProps) {
  return (
    <div className="flex min-h-svh flex-col">
      <header className="flex h-16 shrink-0 items-center justify-between border-b px-4">
        <div className="flex items-center gap-3">
          <Link to="/" className="flex items-center gap-2 font-semibold">
            <Boxes className="size-5 text-primary" />
            <span>Nebula</span>
          </Link>
          <Separator orientation="vertical" className="mx-1 data-[orientation=vertical]:h-4" />
          <Breadcrumb>
            <BreadcrumbList>
              {breadcrumbs.map((crumb, index) => {
                const isLast = index === breadcrumbs.length - 1;
                return (
                  <div key={`crumb-${index}`} className="contents">
                    <BreadcrumbItem className={index > 0 ? 'hidden md:block' : undefined}>
                      {isLast ? (
                        <BreadcrumbPage>{crumb.label}</BreadcrumbPage>
                      ) : crumb.href ? (
                        <BreadcrumbLink asChild>
                          <Link to={crumb.href}>{crumb.label}</Link>
                        </BreadcrumbLink>
                      ) : (
                        <span>{crumb.label}</span>
                      )}
                    </BreadcrumbItem>
                    {!isLast && <BreadcrumbSeparator className="hidden md:block" />}
                  </div>
                );
              })}
            </BreadcrumbList>
          </Breadcrumb>
        </div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="sm" asChild>
            <a href="https://nebula.orbitalhq.com" target="_blank" rel="noopener noreferrer">
              <BookOpen className="size-4" />
              <span className="ml-2 hidden sm:inline">Docs</span>
            </a>
          </Button>
          <Button variant="ghost" size="sm" asChild>
            <a href="https://github.com/orbitalapi/nebula" target="_blank" rel="noopener noreferrer">
              <img src={GithubIcon} alt="GitHub" className="size-4" />
              <span className="ml-2 hidden sm:inline">GitHub</span>
            </a>
          </Button>
        </div>
      </header>
      <main className="flex flex-1 flex-col gap-4 p-4 md:p-6">{children}</main>
    </div>
  );
}
