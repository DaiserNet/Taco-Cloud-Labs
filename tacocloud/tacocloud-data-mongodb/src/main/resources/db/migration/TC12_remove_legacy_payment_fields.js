// TC-12: remove inherited raw payment data without reading or printing values.
// Run once against the Taco Cloud database before deploying the tokenized model.
const orderResult = db.tacoOrder.updateMany(
  { $or: [
    { ccNumber: { $exists: true } },
    { ccExpiration: { $exists: true } },
    { ccCVV: { $exists: true } }
  ] },
  { $unset: { ccNumber: "", ccExpiration: "", ccCVV: "" } }
);

const paymentResult = db.paymentMethod.updateMany(
  { $or: [
    { ccNumber: { $exists: true } },
    { ccExpiration: { $exists: true } },
    { ccCVV: { $exists: true } }
  ] },
  { $unset: { ccNumber: "", ccExpiration: "", ccCVV: "" } }
);

printjson({
  tacoOrdersModified: orderResult.modifiedCount,
  paymentMethodsModified: paymentResult.modifiedCount
});
