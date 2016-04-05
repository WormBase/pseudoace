# Model annotations - input for the ACeDB -> Datomic conversion

The `models.wrm.annot` file is an annotated version of the file here:
https://github.com/WormBase/wormbase-pipeline/blob/master/wspec/models.wrm

When new release is being prepared, annotations are made to the
`models.wrm.annot` file to reflect changes to the ACeDB schema.

When committing changes to this file:

  * Mention the release the changes are being made for
  * Create a git tag of the commit for easy retrospective comparison:
  
   e.g:
   
   ```bash
   #after git commit -m 'Added Latest change'; git push:
   WB_VERSION="WS253"
   NEW_TAG="models.wrm.$WB_VERSION"
   LAST_COMMIT_REF="git log -n1 --oneline | awk '{print $1}'"
   git tag -a -m 'Tagging changes for $WS_VERSION' $NEW_TAG $LAST_COMMIT_REF
   git push --tags
   ```
