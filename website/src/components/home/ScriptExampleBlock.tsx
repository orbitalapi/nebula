import * as React from "react";
import {useState} from "react";
import OpenApiIcon from "./open-api-icon";
import {nebulaExampleSnippets} from "./code-snippets";
import {Snippet} from "@/components/Steps";
import {BigText, SectionHeadingParagraph} from "@/components/common";


export const ScriptExampleBlock = ({
                                nebulaExampleSnippets,
                              }) => {
  return (
    <div>
      <CodeExamples highlightedSnippets={nebulaExampleSnippets}/>
    </div>
  )
}

const CodeExamples = (highlightedCode) => {

  let languageTabs = {
    'Open API': OpenApiIcon,
  };
  const tabCodeSnippets = {
    'Open API': {
      code: nebulaExampleSnippets['kafka-and-http'],
      highlightedCode: highlightedCode.highlightedSnippets['kafka-and-http']
    }

  };

  const [tab, setTab] = useState('Open API');

  return (<div className='max-w-4xl p-4 mx-auto min-h-[590px]'>
    <div className='flex flex-col items-center'>
      <div className={'text-center text-lg'}>
        <BigText>One script, multiple services.</BigText>
        <SectionHeadingParagraph>
          <p>Deploy scripted Kafka, databases, buckets, and APIs using Kotlin</p>
        </SectionHeadingParagraph>
      </div>
    </div>
    <div>
      <Snippet highlightedCode={tabCodeSnippets[tab].highlightedCode} code={tabCodeSnippets[tab].code}/>
    </div>
  </div>)
}

