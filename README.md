# calligraphus

![scriptorium](http://media-2.web.britannica.com/eb-media/76/136776-004-EA1AE575.jpg)

Gather, collate and transcribe Papers We Love chapter data from Meetup.com into YAML.

## Usage

Calligraphus takes a Papers We Love chapter yaml file and outputs chapter API data as yaml:

```shell
$ java -jar calligraphus-0.1.0-standalone.jar -i "/path/to/chapters.yml" -o "path/to/output.yml"
```

You will need to have an API key for Meetup.com in your `ENV` like so:

```shell
export MEETUP_API_KEY="foo"
```

## License

Copyright © 2015 Darren Newton

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
