/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
/* global casper:false */

var lib = require('../lib');

lib.initMessages();
lib.changeWorkingDirectory('quality-gates-spec');
lib.configureCasper();


casper.test.begin('Quality Gates', function suite (test) {
  casper
      .start(lib.buildUrl('quality_gates#show/1'), function () {
        lib.setDefaultViewport();


        lib.mockRequestFromFile('/api/qualitygates/app', 'app.json');
        lib.mockRequestFromFile('/api/qualitygates/list', 'list.json');
        lib.mockRequestFromFile('/api/qualitygates/show?id=1', 'show.json');
      })

      .then(function () {
        casper.evaluate(function () {
          require(['/js/quality-gate/app.js']);
        });
      })

      .then(function () {
        casper.waitForSelector('.active', function () {
          test.assertElementCount('.active', 1);
          test.assertSelectorHasText('.search-navigator-side .active', 'Default Gate');
        });
      })

      .then(function () {
        casper.waitForSelector('.search-navigator-workspace-header', function () {
          test.assertSelectorHasText('.search-navigator-workspace-header', 'Default Gate');
        });
      })

      .then(function () {
        casper.waitForSelector('table.quality-gate-conditions tbody tr:nth-child(9)', function () {
          test.assertElementCount('table.quality-gate-conditions tbody tr', 9);
        });
      })

      .then(function () {
        lib.sendCoverage();
      })

      .run(function () {
        test.done();
      });
});
