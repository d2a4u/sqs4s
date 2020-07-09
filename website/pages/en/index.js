const React = require('react');

const CompLibrary = require('../../core/CompLibrary.js');

const MarkdownBlock = CompLibrary.MarkdownBlock; /* Used to read markdown */
const Container = CompLibrary.Container;
const GridBlock = CompLibrary.GridBlock;

class HomeSplash extends React.Component {
  render() {
    const {siteConfig, language = ''} = this.props;
    const {baseUrl, docsUrl} = siteConfig;
    const docsPart = `${docsUrl ? `${docsUrl}/` : ''}`;
    const langPart = `${language ? `${language}/` : ''}`;
    const docUrl = doc => `${baseUrl}${docsPart}${langPart}${doc}`;

    const SplashContainer = props => (
      <div className="homeContainer">
        <div className="homeSplashFade">
          <div className="wrapper homeWrapper">{props.children}</div>
        </div>
      </div>
    );

    const ProjectTitle = props => (
      <h2 className="projectTitle">
        {props.title}
        <small>{props.tagline}</small>
      </h2>
    );

    const PromoSection = props => (
      <div className="section promoSection">
        <div className="promoRow">
          <div className="pluginRowBlock">{props.children}</div>
        </div>
      </div>
    );

    const Button = props => (
      <div className="pluginWrapper buttonWrapper">
        <a className="button" href={props.href} target={props.target}>
          {props.children}
        </a>
      </div>
    );

    return (
      <SplashContainer>
        <div className="inner">
          <ProjectTitle tagline={siteConfig.tagline} title={siteConfig.title} />
          <PromoSection>
            <Button href={docUrl('overview.html')}>Documentation</Button>
            <Button href={siteConfig.repoUrl}>Github</Button>
          </PromoSection>
        </div>
      </SplashContainer>
    );
  }
}

class Index extends React.Component {
  render() {
    const {config: siteConfig, language = ''} = this.props;
    const {baseUrl} = siteConfig;

    const Block = props => (
      <Container
        padding={['bottom', 'top']}
        id={props.id}
        background={props.background}>
        <GridBlock
          align="center"
          contents={props.children}
          layout={props.layout}
        />
      </Container>
    );

    const Badges = () => (
      <div
        className="productShowcaseSection"
        style={{textAlign: 'center'}}>
        <MarkdownBlock>
        [![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
        [![Build Status](https://travis-ci.com/d2a4u/sqs4s.svg?branch=master)](https://travis-ci.com/d2a4u/sqs4s)
        [![Codacy Badge](https://api.codacy.com/project/badge/Grade/8a331de033cb4700acddb175af4148bb)](https://www.codacy.com/app/d2a4u/sqs4s?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=d2a4u/sqs4s&amp;utm_campaign=Badge_Grade)
        [![Download](https://api.bintray.com/packages/d2a4u/sqs4s/sqs4s-native/images/download.svg)](https://bintray.com/d2a4u/sqs4s/sqs4s-native/_latestVersion)

        </MarkdownBlock>
      </div>
    );
    const Index = () => (
      <div>
        <MarkdownBlock>
        ## Get Started
        </MarkdownBlock>
        Add Bintray resolver:
        <MarkdownBlock>
        ```
        resolvers += Resolver.bintrayRepo("d2a4u", "sqs4s")
        ```
        </MarkdownBlock>
        <MarkdownBlock>
        Add the following to your `build.sbt`, see the badge above for latest version. Supports Scala 2.12 and 2.13.
        </MarkdownBlock>
        <MarkdownBlock>
        ```
        libraryDependencies += "io.sqs4s" %% "sqs4s-native" % "LATEST_VERSION"
        ```
        </MarkdownBlock>
      </div>
    );

    return (
      <div>
        <HomeSplash siteConfig={siteConfig} language={language} />
        <div className="mainContainer">
            <div className="index">
              <Badges />
              <Index />
            </div>
        </div>
      </div>
    );
  }
}

module.exports = Index;
