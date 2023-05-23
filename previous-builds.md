---
layout: home
title: Builds
permalink: /previous/
---

<ul>
{% for post in site.posts %}
  <li>
    <a href="{{ post.url | relative_url }}">{{ post.title }} {{ post.date | date: "%Y-%m-%d" }}</a>
  </li>
{% endfor %}
</ul>
