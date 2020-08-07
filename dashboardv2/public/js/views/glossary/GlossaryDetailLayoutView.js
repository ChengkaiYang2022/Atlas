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
    'hbs!tmpl/glossary/GlossaryDetailLayoutView_tmpl',
    'utils/Utils',
    'utils/Messages',
    'utils/Globals',
    'utils/CommonViewFunction',
    'collection/VGlossaryList'
], function(require, Backbone, GlossaryDetailLayoutViewTmpl, Utils, Messages, Globals, CommonViewFunction, VGlossaryList) {
    'use strict';

    var GlossaryDetailLayoutView = Backbone.Marionette.LayoutView.extend(
        /** @lends GlossaryDetailLayoutView */
        {
            _viewName: 'GlossaryDetailLayoutView',

            template: GlossaryDetailLayoutViewTmpl,

            /** Layout sub regions */
            regions: {
                RSearchResultLayoutView: "#r_searchResultLayoutView",
                RTagTableLayoutView: "#r_tagTableLayoutView",
                RRelationLayoutView: "#r_relationLayoutView"
            },
            templateHelpers: function() {
                return {
                    isTermView: this.isTermView,
                    isCategoryView: this.isCategoryView
                };
            },

            /** ui selector cache */
            ui: {
                details: "[data-id='details']",
                editButton: "[data-id='editButton']",
                title: "[data-id='title']",
                shortDescription: "[data-id='shortDescription']",
                longDescription: "[data-id='longDescription']",

                categoryList: "[data-id='categoryList']",
                removeCategory: "[data-id='removeCategory']",
                categoryClick: "[data-id='categoryClick']",
                addCategory: "[data-id='addCategory']",

                termList: "[data-id='termList']",
                removeTerm: "[data-id='removeTerm']",
                termClick: "[data-id='termClick']",
                addTerm: "[data-id='addTerm']",

                tagList: "[data-id='tagListTerm']",
                removeTag: '[data-id="removeTagTerm"]',
                tagClick: '[data-id="tagClickTerm"]',
                addTag: '[data-id="addTagTerm"]',
            },
            /** ui events hash */
            events: function() {
                var events = {};
                events["click " + this.ui.categoryClick] = function(e) {
                    if (e.target.nodeName.toLocaleLowerCase() == "i") {
                        this.onClickRemoveAssociationBtn(e);
                    } else {
                        var guid = $(e.currentTarget).data('guid'),
                            gId = this.data.anchor && this.data.anchor.glossaryGuid,
                            categoryObj = _.find(this.data.categories, { "categoryGuid": guid });
                        this.glossary.selectedItem = { "type": "GlossaryCategory", "guid": guid, "model": categoryObj };
                        Utils.setUrl({
                            url: '#!/glossary/' + guid,
                            mergeBrowserUrl: false,
                            urlParams: { gType: "category", viewType: "category", fromView: "glossary", gId: gId },
                            trigger: true,
                            updateTabState: true
                        });
                    }
                };
                events["click " + this.ui.termClick] = function(e) {
                    if (e.target.nodeName.toLocaleLowerCase() == "i") {
                        this.onClickRemoveAssociationBtn(e);
                    } else {
                        var guid = $(e.currentTarget).data('guid'),
                            gId = this.data.anchor && this.data.anchor.glossaryGuid,
                            termObj = _.find(this.data.terms, { "termGuid": guid });
                        this.glossary.selectedItem = { "type": "GlossaryTerm", "guid": guid, "model": termObj };
                        Utils.setUrl({
                            url: '#!/glossary/' + guid,
                            mergeBrowserUrl: false,
                            urlParams: { gType: "term", viewType: "term", fromView: "glossary", gId: gId },
                            trigger: true,
                            updateTabState: true
                        });
                    }
                };
                events["click " + this.ui.tagClick] = function(e) {
                    if (e.target.nodeName.toLocaleLowerCase() == "i") {
                        this.onClickTagCross(e);
                    } else {
                        Utils.setUrl({
                            url: '#!/tag/tagAttribute/' + e.currentTarget.textContent,
                            mergeBrowserUrl: false,
                            trigger: true
                        });
                    }
                };
                events["click " + this.ui.editButton] = function(e) {
                    var that = this,
                        model = this.glossaryCollection.fullCollection.get(this.guid);
                    if (this.isGlossaryView) {
                        CommonViewFunction.createEditGlossaryCategoryTerm({
                            "model": model,
                            "isGlossaryView": this.isGlossaryView,
                            "collection": this.glossaryCollection,
                            "callback": function(sModel) {
                                var data = sModel.toJSON();
                                model.set(data, { silent: true }); // update glossaryCollection
                                that.data = data;
                                that.renderDetails(that.data);
                                that.glossaryCollection.trigger("update:details", { isGlossaryUpdate: true });
                            }
                        });
                    } else {
                        CommonViewFunction.createEditGlossaryCategoryTerm({
                            "isTermView": this.isTermView,
                            "isCategoryView": this.isCategoryView,
                            "model": this.data,
                            "collection": this.glossaryCollection,
                            "callback": function(data) {
                                if (data.name != that.data.name) {
                                    var glossary = that.glossaryCollection.fullCollection.get(data.anchor.glossaryGuid);
                                    if (that.isTermView) {
                                        _.find(glossary.get('terms'), function(obj) {
                                            if (obj.termGuid == data.guid) {
                                                obj.displayText = data.name
                                            }
                                        });
                                    } else if (!data.parentCategory) {
                                        _.find(glossary.get('categories'), function(obj) {
                                            if (obj.categoryGuid == data.guid) {
                                                obj.displayText = data.name
                                            }
                                        });
                                    }
                                    that.glossaryCollection.trigger("update:details", { data: that.data });
                                }
                                that.data = data;
                                that.renderDetails(that.data);
                            }
                        });
                    }
                };
                events["click " + this.ui.addTerm] = 'onClickAddTermBtn';
                events["click " + this.ui.addCategory] = 'onClickAddCategoryBtn';
                events["click " + this.ui.addTag] = 'onClickAddTagBtn';
                return events;
            },
            /**
             * intialize a new GlossaryDetailLayoutView Layout
             * @constructs
             */
            initialize: function(options) {
                _.extend(this, _.pick(options, 'guid', 'glossaryCollection', 'glossary', 'collection', 'typeHeaders', 'value', 'entityDefCollection', 'enumDefCollection', 'classificationDefCollection'));
                if (this.value && this.value.gType) {
                    if (this.value.gType == "category") {
                        this.isCategoryView = true;
                    } else if (this.value.gType == "term") {
                        this.isTermView = true;
                    } else {
                        this.isGlossaryView = true;
                    }
                }
                this.selectedTermAttribute = null;
            },
            onRender: function() {
                this.$('.fontLoader-relative').show();
                this.getData();
                this.bindEvents();
            },
            bindEvents: function() {
                var that = this;
            },
            getData: function() {
                if (this.isGlossaryView) {
                    if (this.glossaryCollection.fullCollection.length) {
                        this.data = this.glossaryCollection.fullCollection.get(this.guid).toJSON();
                        this.glossaryCollection.trigger("data:updated", $.extend(true, {}, this.data));
                        this.renderDetails(this.data);
                    } else {
                        this.listenTo(this.glossaryCollection.fullCollection, "reset ", function(skip) {
                            var foundGlossary = this.glossaryCollection.fullCollection.get(this.guid);
                            this.data = foundGlossary ? foundGlossary.toJSON() : null;
                            this.glossaryCollection.trigger("data:updated", $.extend(true, {}, this.data));
                            if (this.data == null) {
                                this.glossary.selectedItem = {};
                                Utils.setUrl({
                                    url: '#!/glossary',
                                    mergeBrowserUrl: false,
                                    urlParams: null,
                                    trigger: true,
                                    updateTabState: true
                                });
                            }
                            this.renderDetails(this.data);
                        }, this);
                    }
                } else {;
                    Utils.showTitleLoader(this.$('.page-title .fontLoader'), this.ui.details);
                    var getApiFunctionKey = "getCategory",
                        that = this;
                    if (this.isTermView) {
                        getApiFunctionKey = "getTerm";
                    }
                    this.glossaryCollection[getApiFunctionKey]({
                        "guid": this.guid,
                        "ajaxOptions": {
                            success: function(data) {
                                if (that.isDestroyed) {
                                    return;
                                }
                                if (that.isTermView) {
                                    var obj = {
                                        "guid": that.guid,
                                        "entityDefCollection": that.entityDefCollection,
                                        "typeHeaders": that.typeHeaders,
                                        "tagCollection": that.collection,
                                        "enumDefCollection": that.enumDefCollection,
                                        "classificationDefCollection": that.classificationDefCollection,
                                        "glossaryCollection": that.glossaryCollection,
                                        "getSelectedTermAttribute": function() {
                                            return that.selectedTermAttribute;
                                        },
                                        "setSelectedTermAttribute": function(val) {
                                            that.selectedTermAttribute = val;
                                        }
                                    }
                                    that.renderSearchResultLayoutView(obj);
                                    that.renderTagTableLayoutView(obj);
                                    that.renderRelationLayoutView(obj);
                                }
                                that.data = data;
                                that.glossaryCollection.trigger("data:updated", $.extend(true, {}, data));
                                that.glossary.selectedItem.model = data;
                                that.glossary.selectedItem.guid = data.guid;
                                that.renderDetails(data)
                            },
                            cust_error: function() {}
                        }
                    });
                }
            },
            renderDetails: function(data) {
                Utils.hideTitleLoader(this.$('.fontLoader'), this.ui.details);
                if (data) {
                    this.ui.title.text(data.name || data.displayText || data.qualifiedName);
                    this.ui.shortDescription.text(data.shortDescription);
                    this.ui.longDescription.text(data.longDescription);
                    this.generateCategories(data.categories);
                    this.generateTerm(data.terms);
                    this.generateTag(data.classifications);
                } else {
                    this.ui.title.text("No Data found");
                }
            },
            generateCategories: function(data) {
                var that = this,
                    categories = "";
                _.each(data, function(val) {
                    var name = _.escape(val.displayText);
                    categories += '<span data-guid="' + val.categoryGuid + '"" class="btn btn-action btn-sm btn-icon btn-blue" title=' + _.escape(name) + ' data-id="categoryClick"><span>' + name + '</span><i class="fa fa-close" data-id="removeCategory" data-type="category" title="Remove Category"></i></span>';
                });
                this.ui.categoryList.find("span.btn").remove();
                this.ui.categoryList.prepend(categories);
            },
            generateTerm: function(data) {
                var that = this,
                    terms = "";
                _.each(data, function(val) {
                    var name = _.escape(val.displayText);
                    terms += '<span data-guid="' + val.termGuid + '"" class="btn btn-action btn-sm btn-icon btn-blue" title=' + _.escape(name) + ' data-id="termClick"><span>' + name + '</span><i class="fa fa-close" data-id="removeTerm" data-type="term" title="Remove Term"></i></span>';
                });
                this.ui.termList.find("span.btn").remove();
                this.ui.termList.prepend(terms);

            },
            generateTag: function(tagObject) {
                var that = this,
                    tagData = "";
                _.each(tagObject, function(val) {
                    tagData += '<span class="btn btn-action btn-sm btn-icon btn-blue" title=' + val.typeName + ' data-id="tagClickTerm"><span>' + val.typeName + '</span><i class="fa fa-close" data-id="removeTagTerm" data-type="tag" title="Remove Tag"></i></span>';
                });
                this.ui.tagList.find("span.btn").remove();
                this.ui.tagList.prepend(tagData);
            },
            onClickAddTermBtn: function(e) {
                var that = this;
                require(['views/glossary/AssignTermLayoutView'], function(AssignTermLayoutView) {
                    var glossary = that.glossaryCollection;
                    if (that.value && that.value.gId) {
                        var foundModel = that.glossaryCollection.find({ guid: that.value.gId });
                        if (foundModel) {
                            glossary = new VGlossaryList([foundModel.toJSON()], {
                                comparator: function(item) {
                                    return item.get("name");
                                }
                            });
                        }
                    }

                    var view = new AssignTermLayoutView({
                        categoryData: that.data,
                        associatedTerms: that.data && that.data.terms && that.data.terms.length > 0 ? that.data.terms : [],
                        isCategoryView: that.isCategoryView,
                        callback: function() {
                            that.getData();
                        },
                        glossaryCollection: glossary
                    });
                    view.modal.on('ok', function() {
                        that.hideLoader();
                    });
                });
            },
            onClickAddCategoryBtn: function(e) {
                var that = this;
                require(['views/glossary/AssignTermLayoutView'], function(AssignTermLayoutView) {
                    var glossary = that.glossaryCollection;
                    if (that.value && that.value.gId) {
                        var foundModel = that.glossaryCollection.find({ guid: that.value.gId });
                        if (foundModel) {
                            glossary = new VGlossaryList([foundModel.toJSON()], {
                                comparator: function(item) {
                                    return item.get("name");
                                }
                            });
                        }
                    }
                    var view = new AssignTermLayoutView({
                        termData: that.data,
                        isTermView: that.isTermView,
                        callback: function() {
                            that.getData();
                        },
                        glossaryCollection: glossary
                    });
                    view.modal.on('ok', function() {
                        that.hideLoader();
                    });
                });
            },
            onClickAddTagBtn: function(e) {
                var that = this;
                require(['views/tag/AddTagModalView'], function(AddTagModalView) {
                    var tagList = [];
                    _.map(that.data.classifications, function(obj) {
                        if (obj.entityGuid === that.guid) {
                            tagList.push(obj.typeName);
                        }
                    });
                    var view = new AddTagModalView({
                        guid: that.guid,
                        tagList: tagList,
                        callback: function() {
                            that.getData();
                        },
                        showLoader: that.showLoader.bind(that),
                        hideLoader: that.hideLoader.bind(that),
                        collection: that.classificationDefCollection,
                        enumDefCollection: that.enumDefCollection
                    });
                });
            },
            onClickTagCross: function(e) {
                var that = this,
                    tagName = $(e.currentTarget).text(),
                    termName = this.data.name;
                CommonViewFunction.deleteTag(_.extend({}, {
                    msg: "<div class='ellipsis'>Remove: " + "<b>" + _.escape(tagName) + "</b> assignment from" + " " + "<b>" + termName + "?</b></div>",
                    titleMessage: Messages.removeTag,
                    okText: "Remove",
                    showLoader: that.showLoader.bind(that),
                    hideLoader: that.hideLoader.bind(that),
                    tagName: tagName,
                    guid: that.guid,
                    callback: function() {
                        that.getData();
                    }
                }));
            },
            onClickRemoveAssociationBtn: function(e) {
                var $el = $(e.currentTarget),
                    guid = $el.data('guid'),
                    name = $el.text(),
                    that = this;
                CommonViewFunction.removeCategoryTermAssociation({
                    selectedGuid: guid,
                    model: that.data,
                    collection: that.glossaryCollection,
                    msg: "<div class='ellipsis'>Remove: " + "<b>" + _.escape(name) + "</b> assignment from" + " " + "<b>" + that.data.name + "?</b></div>",
                    titleMessage: Messages.glossary[that.isTermView ? "removeCategoryfromTerm" : "removeTermfromCategory"],
                    isCategoryView: that.isCategoryView,
                    isTermView: that.isTermView,
                    buttonText: "Remove",
                    showLoader: that.hideLoader.bind(that),
                    hideLoader: that.hideLoader.bind(that),
                    callback: function() {
                        that.getData();
                    }
                });
            },
            showLoader: function() {
                Utils.showTitleLoader(this.$('.page-title .fontLoader'), this.ui.details);
            },
            hideLoader: function() {
                Utils.hideTitleLoader(this.$('.page-title .fontLoader'), this.ui.details);
            },
            renderTagTableLayoutView: function(options) {
                var that = this;
                require(['views/tag/TagDetailTableLayoutView'], function(TagDetailTableLayoutView) {
                    if (that.RTagTableLayoutView) {
                        that.RTagTableLayoutView.show(new TagDetailTableLayoutView(_.extend({}, options, {
                            "entityName": that.ui.title.text(),
                            "fetchCollection": that.getData.bind(that),
                            "entity": that.data
                        })));
                    }
                });
            },
            renderSearchResultLayoutView: function(options) {
                var that = this;

                require(['views/search/SearchResultLayoutView'], function(SearchResultLayoutView) {
                    var value = {
                        'tag': "PII",
                        'searchType': 'basic'
                    };
                    if (that.RSearchResultLayoutView) {
                        that.RSearchResultLayoutView.show(new SearchResultLayoutView(_.extend({}, options, {
                            "value": { "searchType": "basic", "term": that.data.qualifiedName },
                            "fromView": "glossary"
                        })));
                    }
                });
            },
            renderRelationLayoutView: function(options) {
                var that = this;
                require(['views/glossary/TermRelationAttributeLayoutView'], function(TermRelationAttributeLayoutView) {
                    if (that.RRelationLayoutView) {
                        that.RRelationLayoutView.show(new TermRelationAttributeLayoutView(_.extend({}, options, {
                            "entityName": that.ui.title.text(),
                            "fetchCollection": that.getData.bind(that),
                            "data": that.data
                        })));
                    }
                });
            },
        });
    return GlossaryDetailLayoutView;
});