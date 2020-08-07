/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

define(['require',
    'backbone',
    'hbs!tmpl/profile/ProfileTableLayoutView_tmpl',
    'collection/VProfileList',
    'utils/Utils',
    'utils/Messages',
    'utils/Globals',
    'moment',
    'collection/VCommonList',
    'models/VEntity',
    'sparkline'
], function(require, Backbone, ProfileTableLayoutViewTmpl, VProfileList, Utils, Messages, Globals, moment, VCommonList, VEntity, sparkline) {
    'use strict';

    var ProfileTableLayoutView = Backbone.Marionette.LayoutView.extend(
        /** @lends ProfileTableLayoutView */
        {
            _viewName: 'ProfileTableLayoutView',

            template: ProfileTableLayoutViewTmpl,

            /** Layout sub regions */
            regions: {
                RProfileTableLayoutView: '#r_profileTableLayoutView'
            },
            /** ui selector cache */
            ui: {},

            /** ui events hash */
            events: function() {
                var events = {};
                events["click " + this.ui.addTag] = 'checkedValue';
                return events;
            },
            /**
             * intialize a new ProfileTableLayoutView Layout
             * @constructs
             */
            initialize: function(options) {
                _.extend(this, _.pick(options, 'profileData', 'guid', 'entityDetail'));
                var that = this;
                this.entityModel = new VEntity();
                this.profileCollection = new VCommonList([], {
                    comparator: function(item) {
                        return item.get('position') || 999;
                    }
                });
                _.each(this.entityDetail.columns, function(obj) {
                    if (obj.attributes.profileData !== null) {
                        var profileObj = Utils.getProfileTabType(obj.attributes.profileData.attributes, true);
                        var changeValueObj = {}
                        if (profileObj && profileObj.type) {
                            if (profileObj.type === "numeric") {
                                changeValueObj['averageLength'] = 0;
                                changeValueObj['maxLength'] = 0;
                            }
                            if (profileObj.type === "string") {
                                changeValueObj['minValue'] = 0;
                                changeValueObj['maxValue'] = 0;
                                changeValueObj['meanValue'] = 0;
                                changeValueObj['medianValue'] = 0;
                            }
                            if (profileObj.type === "date") {
                                changeValueObj['averageLength'] = 0;
                                changeValueObj['maxLength'] = 0;
                                changeValueObj['minValue'] = 0;
                                changeValueObj['maxValue'] = 0;
                                changeValueObj['meanValue'] = 0;
                                changeValueObj['medianValue'] = 0;
                            }
                        }

                        that.profileCollection.fullCollection.add(_.extend({}, obj.attributes, obj.attributes.profileData.attributes, changeValueObj, { guid: obj.guid, position: obj.attributes ? obj.attributes.position : null }));
                    }
                });
                this.bindEvents();
            },
            onRender: function() {
                this.fetchEntity();
                this.renderTableLayoutView();
                if (this.entityDetail) {
                    if (this.guid && this.entityDetail.name) {
                        this.$('.table_name .graphval').html('<b><a href="#!/detailPage/' + this.guid + '">' + this.entityDetail.name + '</a></b>');
                    }
                    var profileData = this.entityDetail.profileData;
                    if (profileData && profileData.attributes && profileData.attributes.rowCount) {
                        this.$('.rowValue .graphval').html('<b>' + d3.format("2s")(profileData.attributes.rowCount).replace('G', 'B') + '</b>');
                    }
                    this.$('.table_created .graphval').html('<b>' + (this.entityDetail.createTime ? moment(this.entityDetail.createTime).format("LL") : "--") + '</b>');
                }
            },
            fetchEntity: function(argument) {
                var that = this;
                this.entityModel.getEntity(this.entityDetail.db.guid, {
                    skipDefaultError: true,
                    success: function(data) {
                        var entity = data.entity;
                        if (entity.attributes) {
                            if (entity.guid) {
                                that.$('.db_name .graphval').html('<b><a href="#!/detailPage/' + entity.guid + "?profile=true" + '">' + Utils.getName(entity) + '</a></b>');
                            }
                        }
                    }
                });
            },
            bindEvents: function() {
                this.listenTo(this.profileCollection, 'backgrid:refresh', function(model, checked) {
                    this.renderGraphs();
                }, this);
            },
            renderTableLayoutView: function() {
                var that = this;
                require(['utils/TableLayout'], function(TableLayout) {
                    var cols = new Backgrid.Columns(that.getAuditTableColumns());
                    that.RProfileTableLayoutView.show(new TableLayout(_.extend({}, {
                        columns: cols,
                        collection: that.profileCollection,
                        includeFilter: false,
                        includePagination: true,
                        includePageSize: false,
                        includeFooterRecords: true,
                        gridOpts: {
                            className: "table table-hover backgrid table-quickMenu",
                            emptyText: 'No records found!'
                        }
                    })));
                    that.renderGraphs();
                });
            },
            renderGraphs: function() {
                this.$('.sparklines').sparkline('html', { enableTagOptions: true });
                this.$('.sparklines').bind('sparklineClick', function(ev) {
                    var id = $(ev.target).data().guid;
                    Utils.setUrl({
                        url: '#!/detailPage/' + id,
                        mergeBrowserUrl: false,
                        trigger: true,
                        urlParams: {
                            tabActive: 'profile'
                        }
                    });
                });
            },
            getAuditTableColumns: function() {
                var that = this;
                return this.profileCollection.constructor.getTableCols({
                    name: {
                        label: "Name",
                        cell: "Html",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        direction: 'ascending',
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                return '<div><a href="#!/detailPage/' + model.get('guid') + '?profile=true">' + rawValue + '</a></div>';
                            }
                        })
                    },
                    type: {
                        label: "Type",
                        cell: "String",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                    },
                    nonNullData: {
                        label: "% NonNull",
                        cell: "Html",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        width: "180",
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                if (rawValue < 50) {
                                    var barClass = ((rawValue > 30) && (rawValue <= 50)) ? "progress-bar-warning" : "progress-bar-danger";
                                } else {
                                    var barClass = "progress-bar-success";
                                }
                                return '<div class="progress cstm_progress" title="' + rawValue + '%"><div class="progress-bar ' + barClass + ' cstm_success-bar progress-bar-striped" style="width:' + rawValue + '%">' + rawValue + '%</div></div>'
                            }
                        })
                    },

                    distributionDecile: {
                        label: "Distribution",
                        cell: "Html",
                        editable: false,
                        sortable: false,
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                var sparkarray = [];
                                var distibutionObj = Utils.getProfileTabType(model.toJSON());
                                if (distibutionObj) {
                                    _.each(distibutionObj.actualObj, function(obj) {
                                        sparkarray.push(obj.count);
                                    })
                                }

                                return '<span data-guid="' + model.get('guid') + '" class="sparklines" sparkType="bar" sparkBarColor="#38BB9B" values="' + sparkarray.join(',') + '"></span>'
                            }
                        })
                    },
                    cardinality: {
                        label: "Cardinality",
                        cell: "Number",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle'
                    },
                    minValue: {
                        label: "Min",
                        cell: "Number",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                var profileObj = Utils.getProfileTabType(model.toJSON(), true);
                                if (profileObj && profileObj.type === "numeric") {
                                    return rawValue;
                                }
                                return "-";
                            }
                        })
                    },
                    maxValue: {
                        label: "Max",
                        cell: "Number",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                var profileObj = Utils.getProfileTabType(model.toJSON(), true);
                                if (profileObj && profileObj.type === "numeric") {
                                    return rawValue;
                                }
                                return "-";
                            }
                        })
                    },
                    averageLength: {
                        label: "Average Length",
                        cell: "Number",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                var profileObj = Utils.getProfileTabType(model.toJSON(), true);
                                if (profileObj && profileObj.type === "string") {
                                    return rawValue;
                                }
                                return "-";
                            }
                        })
                    },
                    maxLength: {
                        label: "Max Length",
                        cell: "Number",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                var profileObj = Utils.getProfileTabType(model.toJSON(), true);
                                if (profileObj && profileObj.type === "string") {
                                    return rawValue;
                                }
                                return "-";
                            }
                        })
                    },
                    meanValue: {
                        label: "Mean",
                        cell: "Number",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                var profileObj = Utils.getProfileTabType(model.toJSON(), true);
                                if (profileObj && profileObj.type === "numeric") {
                                    return rawValue;
                                }
                                return "-";
                            }
                        })
                    },
                    medianValue: {
                        label: "Median",
                        cell: "Number",
                        editable: false,
                        sortable: true,
                        sortType: 'toggle',
                        formatter: _.extend({}, Backgrid.CellFormatter.prototype, {
                            fromRaw: function(rawValue, model) {
                                var profileObj = Utils.getProfileTabType(model.toJSON(), true);
                                if (profileObj && profileObj.type === "numeric") {
                                    return rawValue;
                                }
                                return "-";
                            }
                        })
                    }
                }, this.profileCollection);

            }

        });
    return ProfileTableLayoutView;
});