import { useRouter } from 'next/router'
import { useConfig } from 'nextra-theme-docs'

export default {
    logo: <span>Nebula</span>,
    project: {
        link: 'https://github.com/orbitalapi/nebula'
    },
    chat: {
        link: 'https://join.slack.com/t/orbitalapi/shared_invite/zt-697laanr-DHGXXak5slqsY9DqwrkzHg',
        icon: (
            <>
                <svg width="24" height="24" viewBox="0 0 128 128">
                    <title>Slack</title>
                    <path
                        d="M27.255 80.719c0 7.33-5.978 13.317-13.309 13.317C6.616 94.036.63 88.049.63 80.719s5.987-13.317 13.317-13.317h13.309zm6.709 0c0-7.33 5.987-13.317 13.317-13.317s13.317 5.986 13.317 13.317v33.335c0 7.33-5.986 13.317-13.317 13.317-7.33 0-13.317-5.987-13.317-13.317zm0 0"
                        fill="#de1c59"></path>
                    <path
                        d="M47.281 27.255c-7.33 0-13.317-5.978-13.317-13.309C33.964 6.616 39.951.63 47.281.63s13.317 5.987 13.317 13.317v13.309zm0 6.709c7.33 0 13.317 5.987 13.317 13.317s-5.986 13.317-13.317 13.317H13.946C6.616 60.598.63 54.612.63 47.281c0-7.33 5.987-13.317 13.317-13.317zm0 0"
                        fill="#35c5f0"></path>
                    <path
                        d="M100.745 47.281c0-7.33 5.978-13.317 13.309-13.317 7.33 0 13.317 5.987 13.317 13.317s-5.987 13.317-13.317 13.317h-13.309zm-6.709 0c0 7.33-5.987 13.317-13.317 13.317s-13.317-5.986-13.317-13.317V13.946C67.402 6.616 73.388.63 80.719.63c7.33 0 13.317 5.987 13.317 13.317zm0 0"
                        fill="#2eb57d"></path>
                    <path
                        d="M80.719 100.745c7.33 0 13.317 5.978 13.317 13.309 0 7.33-5.987 13.317-13.317 13.317s-13.317-5.987-13.317-13.317v-13.309zm0-6.709c-7.33 0-13.317-5.987-13.317-13.317s5.986-13.317 13.317-13.317h33.335c7.33 0 13.317 5.986 13.317 13.317 0 7.33-5.987 13.317-13.317 13.317zm0 0"
                        fill="#ebb02e"></path>
                </svg>
                <span className="_sr-only">Slack</span>
            </>
        )
    },
    docsRepositoryBase: 'https://github.com/orbitalapi/nebula/docs',
    footer: {
        content: (
            <span>Built by &nbsp;<a className="underline" href="https://orbitalhq.com" target="_blank">Orbital</a>, to make demos suck less.</span>
        )
    },
    head: () => {
        const { asPath, defaultLocale, locale } = useRouter()
        const { frontMatter, title } = useConfig()
        const url =
            'https://nebula.orbitalhq.com/' +
            (defaultLocale === locale ? asPath : `/${locale}${asPath}`)

        const metaTitle = 'Nebula: ' + title;
        return (
            <>
                <meta property="og:url" content={url} />
                <title>{metaTitle}</title>
                <meta property="og:title" content={metaTitle} />
                <meta
                    property="og:description"
                    content={frontMatter.description}
                />
            </>
        )
    }
    // ... other theme options
}
