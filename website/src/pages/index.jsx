import {Footer} from '@/components/Footer';
import GetStartedButton from '@/components/GetStartedButton';
import {HeadMetaTags, Paragraph} from '@/components/common';
import {LinkButton} from '@/components/LinkButton';
import * as React from 'react';
import FAQ from '@/components/Faq';
import {OrbitalLogoMark} from "@/components/icons/orbital-logo";
import {TaxiLogo} from "@/components/icons/taxi-icon-yellow";
import {getStartedSnippet, microservicesCodeSnippets, nebulaExampleSnippets} from "@/components/home/code-snippets";
import {ScriptExampleBlock} from "@/components/home/ScriptExampleBlock";
import {Snippet} from "@/components/Steps";

function HeroSection() {
  return (
    <header className='relative'>
      <div className='sm:px-6 md:px-8 dark:bg-brand-background'>
        <div className='font-brand dark:text-white mx-auto max-w-8xl flex items-center gap-8 flex-col my-16 relative'>
          <h1 className='font-bold lg:text-8xl'>
            Nebula
          </h1>
          <h2 className='font-light lg:text-6xl text-4xl leading-tight text-center'>
            Scriptable test environments
          </h2>
          <p className='lg:text-2xl font-light text-lg text-center'>
            Topics that emit. Tables with data. Buckets with files.
          </p>
          <div className='sm:mt-10 mt-8 mb-3 flex justify-center gap-6 text-base md:text-lg flex-wrap'>
            <GetStartedButton/>
            <LinkButton styles="hidden md:flex" link='/desktop' label='Try the desktop app'/>
          </div>
          <div className={'flex items-center gap-4'}>
            <div>Developed by</div>
            <a href='https://orbitalhq.com'>
              <OrbitalLogoMark className="hidden h-7 w-auto fill-slate-700 dark:fill-sky-100 lg:block"/>
            </a>

          </div>
        </div>
      </div>
    </header>
  );
}

export default function Home(
  {
    highlightedGetStartedSnippet,
    nebulaExampleSnippets,
  }
) {
  return (
    <>
      <HeadMetaTags title="Nebula - Scriptable test environments"/>
      <div className='overflow-hidden dark:bg-brand-background'>
        <HeroSection/>
      </div>

      <div className='mx-auto max-w-8xl flex items-center gap-8 flex-col pb-16'>
        <Snippet highlightedCode={highlightedGetStartedSnippet['get-started']} code={getStartedSnippet['get-started']}/>
      </div>
      <div className='overflow-hidden'>
        <ScriptExampleBlock nebulaExampleSnippets={nebulaExampleSnippets} />
      </div>
      <Footer/>
    </>
  );
}

export function getStaticProps() {
  let {highlightCodeSnippets} = require('@/components/Guides/Snippets');

  return {
    props: {
      highlightedGetStartedSnippet: highlightCodeSnippets(getStartedSnippet),
      nebulaExampleSnippets: highlightCodeSnippets(nebulaExampleSnippets),
    }
  };
}
